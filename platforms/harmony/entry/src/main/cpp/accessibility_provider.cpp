#include "accessibility_provider.h"
#include <arkui/native_interface_accessibility.h>
#include <hilog/log.h>
#include <algorithm>
#include <cmath>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

namespace pod_a11y {
namespace {
constexpr int kMissing = ARKUI_ACCESSIBILITY_NATIVE_RESULT_FAILED;
constexpr int kBad = ARKUI_ACCESSIBILITY_NATIVE_RESULT_BAD_PARAMETER;
constexpr int32_t kRootParent = -2100000; // ArkUI virtual-root contract.
struct Node {
  int32_t id = 0, parent = kRootParent, role = 8;
  uint16_t state = 0;
  uint8_t actions = 0;
  std::string label, value, hint, description;
  ArkUI_AccessibleRect rect{};
  std::vector<int64_t> children;
};
struct Event { Node node; ArkUI_AccessibilityEventType type; bool focused; };
std::mutex mutex;
std::vector<Node> nodes;
Node root;
std::optional<uint64_t> hash;
int32_t focused = 0;
ArkUI_AccessibilityProvider* provider = nullptr;
Action actionSink;
std::vector<Event> events;
uint32_t lastWidth = 0, lastHeight = 0;
double lastX = 0, lastY = 0;
std::array<std::string, 6> stateLabels{"Checked", "Not checked", "Partially checked", "Expanded", "Collapsed", "Busy"};
void describe(Node& node) {
  node.description = node.value;
  auto append = [&](const std::string& text) { if (!node.description.empty()) node.description += ", "; node.description += text; };
  if (node.state & 64) append(stateLabels[node.state & 8 ? 2 : node.state & 4 ? 0 : 1]);
  if (node.state & 128) append(stateLabels[node.state & 16 ? 3 : 4]);
  if (node.state & 32) append(stateLabels[5]);
}

void trace(const char* operation, int32_t requestId) {
  OH_LOG_Print(LOG_APP, LOG_DEBUG, 0xD002D00, "PodJSA11y", "%{public}s request=%{public}d", operation, requestId);
}
const Node* find(int64_t id) {
  if (id == -1 || id == 0) return &root;
  auto it = std::find_if(nodes.begin(), nodes.end(), [id](const Node& node) { return node.id == id; });
  return it == nodes.end() ? nullptr : &*it;
}
bool within(const Node& node, int64_t ancestor) {
  if (ancestor == -1 || ancestor == 0 || node.id == ancestor) return true;
  int32_t parent = node.parent;
  for (size_t depth = 0; parent > 0 && depth < nodes.size(); ++depth) {
    if (parent == ancestor) return true;
    const auto* current = find(parent); if (!current) return false;
    parent = current->parent;
  }
  return false;
}
int fill(const Node& node, ArkUI_AccessibilityElementInfo* out, bool hasFocus) {
  if (!out) return kBad;
  static const char* roles[] = {"Text", "Button", "Image", "Text", "Text", "Checkbox", "Toggle", "Slider", "List", "ListItem"};
  OH_ArkUI_AccessibilityElementInfoSetElementId(out, node.id);
  OH_ArkUI_AccessibilityElementInfoSetParentId(out, node.parent);
  OH_ArkUI_AccessibilityElementInfoSetComponentType(out, roles[std::clamp(node.role, 0, 9)]);
  OH_ArkUI_AccessibilityElementInfoSetAccessibilityLevel(out, node.id == 0 ? "no" : "yes");
  OH_ArkUI_AccessibilityElementInfoSetContents(out, node.label.c_str());
  OH_ArkUI_AccessibilityElementInfoSetAccessibilityText(out, node.label.c_str());
  OH_ArkUI_AccessibilityElementInfoSetHintText(out, node.hint.c_str());
  OH_ArkUI_AccessibilityElementInfoSetAccessibilityDescription(out, node.description.c_str());
  auto rect = node.rect;
  OH_ArkUI_AccessibilityElementInfoSetScreenRect(out, &rect);
  OH_ArkUI_AccessibilityElementInfoSetVisible(out, true);
  OH_ArkUI_AccessibilityElementInfoSetEnabled(out, !(node.state & 1));
  OH_ArkUI_AccessibilityElementInfoSetSelected(out, node.state & 2);
  OH_ArkUI_AccessibilityElementInfoSetCheckable(out, node.state & 64);
  OH_ArkUI_AccessibilityElementInfoSetChecked(out, node.state & 4);
  OH_ArkUI_AccessibilityElementInfoSetFocusable(out, node.id != 0);
  OH_ArkUI_AccessibilityElementInfoSetAccessibilityFocused(out, hasFocus);
  OH_ArkUI_AccessibilityElementInfoSetClickable(out, node.actions & 1);
  OH_ArkUI_AccessibilityElementInfoSetScrollable(out, node.actions & 6);
  auto children = node.children;
  OH_ArkUI_AccessibilityElementInfoSetChildNodeIds(out, static_cast<int32_t>(children.size()), children.data());
  std::vector<ArkUI_AccessibleAction> actions;
  if (node.id != 0) actions.push_back({hasFocus ? ARKUI_ACCESSIBILITY_NATIVE_ACTION_TYPE_CLEAR_ACCESSIBILITY_FOCUS : ARKUI_ACCESSIBILITY_NATIVE_ACTION_TYPE_GAIN_ACCESSIBILITY_FOCUS, ""});
  if (node.actions & 1) actions.push_back({ARKUI_ACCESSIBILITY_NATIVE_ACTION_TYPE_CLICK, ""});
  if (node.actions & 2) actions.push_back({ARKUI_ACCESSIBILITY_NATIVE_ACTION_TYPE_SCROLL_FORWARD, ""});
  if (node.actions & 4) actions.push_back({ARKUI_ACCESSIBILITY_NATIVE_ACTION_TYPE_SCROLL_BACKWARD, ""});
  OH_ArkUI_AccessibilityElementInfoSetOperationActions(out, static_cast<int32_t>(actions.size()), actions.data());
  return 0;
}
int append(const Node& node, ArkUI_AccessibilityElementInfoList* list) {
  return fill(node, OH_ArkUI_AddAndGetAccessibilityElementInfo(list), node.id != 0 && focused == node.id);
}
int32_t queryId(int64_t id, ArkUI_AccessibilitySearchMode mode, int32_t request, ArkUI_AccessibilityElementInfoList* list) {
  trace("queryId", request); if (!list || (static_cast<int>(mode) & ~15)) return kBad;
  std::lock_guard lock(mutex); const Node* target = find(id); if (!target) return kMissing;
  if (append(*target, list)) return kBad;
  std::vector<int32_t> emitted{target->id};
  auto add = [&](const Node& node) {
    if (std::find(emitted.begin(), emitted.end(), node.id) != emitted.end()) return 0;
    emitted.push_back(node.id); return append(node, list);
  };
  if (mode & ARKUI_ACCESSIBILITY_NATIVE_SEARCH_MODE_PREFETCH_PREDECESSORS) {
    const Node* parent = find(target->parent);
    while (parent) { if (add(*parent)) return kBad; if (parent->id == 0) break; parent = find(parent->parent); }
  }
  for (const auto& node : nodes) {
    bool include = (mode & ARKUI_ACCESSIBILITY_NATIVE_SEARCH_MODE_PREFETCH_SIBLINGS) && node.parent == target->parent;
    include |= (mode & ARKUI_ACCESSIBILITY_NATIVE_SEARCH_MODE_PREFETCH_CHILDREN) && node.parent == target->id;
    include |= (mode & ARKUI_ACCESSIBILITY_NATIVE_SEARCH_MODE_PREFETCH_RECURSIVE_CHILDREN) && within(node, target->id);
    if (include && add(node)) return kBad;
  }
  return 0;
}
int32_t queryText(int64_t id, const char* text, int32_t request, ArkUI_AccessibilityElementInfoList* list) {
  trace("queryText", request); if (!text || !list) return kBad;
  std::lock_guard lock(mutex); if (!find(id)) return kMissing;
  for (const auto& node : nodes) if (within(node, id) && (node.label.find(text) != std::string::npos || node.value.find(text) != std::string::npos)) {
    if (append(node, list)) return kBad;
  }
  return 0;
}
int32_t queryFocus(int64_t id, ArkUI_AccessibilityFocusType type, int32_t request, ArkUI_AccessibilityElementInfo* out) {
  trace("queryFocus", request); if (!out) return kBad;
  std::lock_guard lock(mutex); const Node* node = focused ? find(focused) : nullptr;
  if (type != ARKUI_ACCESSIBILITY_NATIVE_FOCUS_TYPE_ACCESSIBILITY || !node || !find(id) || !within(*node, id)) return kMissing;
  return fill(*node, out, true);
}
int32_t nextFocus(int64_t id, ArkUI_AccessibilityFocusMoveDirection direction, int32_t request, ArkUI_AccessibilityElementInfo* out) {
  trace("nextFocus", request); if (!out) return kBad;
  std::lock_guard lock(mutex); if (!find(id)) return kMissing;
  if (direction != ARKUI_ACCESSIBILITY_NATIVE_DIRECTION_FORWARD && direction != ARKUI_ACCESSIBILITY_NATIVE_DIRECTION_BACKWARD) return kBad;
  int index = -1;
  if (id > 0) {
    auto it = std::find_if(nodes.begin(), nodes.end(), [id](const Node& node){return node.id == id;});
    index = static_cast<int>(it - nodes.begin());
  }
  index = direction == ARKUI_ACCESSIBILITY_NATIVE_DIRECTION_FORWARD ? index + 1 : index < 0 ? static_cast<int>(nodes.size()) - 1 : index - 1;
  if (index < 0 || static_cast<size_t>(index) >= nodes.size()) return kMissing;
  return fill(nodes[index], out, focused == nodes[index].id);
}
void clearFocusLocked() {
  if (focused) { if (const auto* node = find(focused)) events.push_back({*node, ARKUI_ACCESSIBILITY_NATIVE_EVENT_TYPE_ACCESSIBILITY_FOCUS_CLEARED, false}); }
  focused = 0;
}
int32_t execute(int64_t id, ArkUI_Accessibility_ActionType action, ArkUI_AccessibilityActionArguments*, int32_t request) {
  trace("execute", request);
  Action sink; uint64_t currentHash = 0; uint8_t bit = 0;
  {
    std::lock_guard lock(mutex); const Node* node = find(id); if (!node || id <= 0) return kMissing;
    if (action == ARKUI_ACCESSIBILITY_NATIVE_ACTION_TYPE_GAIN_ACCESSIBILITY_FOCUS) {
      if (focused == id) return kMissing;
      clearFocusLocked(); focused = node->id;
      events.push_back({*node, ARKUI_ACCESSIBILITY_NATIVE_EVENT_TYPE_ACCESSIBILITY_FOCUSED, true});
    } else if (action == ARKUI_ACCESSIBILITY_NATIVE_ACTION_TYPE_CLEAR_ACCESSIBILITY_FOCUS) {
      if (focused != id) return kMissing; clearFocusLocked();
    } else {
      bit = action == ARKUI_ACCESSIBILITY_NATIVE_ACTION_TYPE_CLICK ? 1 : action == ARKUI_ACCESSIBILITY_NATIVE_ACTION_TYPE_SCROLL_FORWARD ? 2 : action == ARKUI_ACCESSIBILITY_NATIVE_ACTION_TYPE_SCROLL_BACKWARD ? 4 : 0;
      if (!bit || !(node->actions & bit) || (node->state & 1) || !hash || !actionSink) return kMissing;
      currentHash = *hash; sink = actionSink;
    }
  }
  if (bit) return sink(static_cast<int32_t>(id), currentHash, bit) ? 0 : kMissing;
  flush(); return 0;
}
int32_t clearFocus() { { std::lock_guard lock(mutex); clearFocusLocked(); } flush(); return 0; }
int32_t cursor(int64_t, int32_t request, int32_t* index) { trace("cursor", request); if (index) *index = -1; return kMissing; }
ArkUI_AccessibilityProviderCallbacks callbacks{queryId, queryText, queryFocus, nextFocus, execute, clearFocus, cursor};
std::string copy(PodAccessibilityText text) {
  std::string result;
  // ArkUI takes NUL-terminated strings; retain text after embedded NULs rather
  // than accidentally truncating a complete semantic label at that boundary.
  for (size_t i = 0; text.bytes && i < text.byte_length; ++i) {
    if (text.bytes[i] == 0) result += "\xef\xbf\xbd";
    else result += static_cast<char>(text.bytes[i]);
  }
  return result;
}
}

bool bind(ArkUI_AccessibilityProvider* target, Action action) {
  if (!target) return false;
  if (OH_ArkUI_AccessibilityProviderRegisterCallback(target, &callbacks) != 0) return false;
  std::lock_guard lock(mutex); provider = target; actionSink = std::move(action); return true;
}
void setStateLabels(const std::array<std::string, 6>& labels) {
  std::lock_guard lock(mutex);
  if (labels == stateLabels) return;
  stateLabels = labels;
  for (auto& node : nodes) describe(node);
  events.push_back({root, ARKUI_ACCESSIBILITY_NATIVE_EVENT_TYPE_PAGE_CONTENT_UPDATE, false});
}
void commit(PodRuntime* runtime, uint32_t width, uint32_t height, double x, double y) {
  PodAccessibilityTree tree{}; if (pod_runtime_accessibility_tree(runtime, &tree)) return;
  std::lock_guard lock(mutex);
  if (hash == tree.content_hash && width == lastWidth && height == lastHeight && x == lastX && y == lastY) return;
  std::vector<Node> next; next.reserve(tree.node_count);
  double sx = static_cast<double>(width) / std::max(1u, pod_runtime_logical_width(runtime));
  double sy = static_cast<double>(height) / std::max(1u, pod_runtime_logical_height(runtime));
  for (size_t index = 0; index < tree.node_count; ++index) {
    PodAccessibilityNode source{}; if (pod_runtime_accessibility_node(runtime, index, &source)) return;
    Node node; node.id = source.id; node.parent = source.parent_id; node.role = source.role; node.state = source.state; node.actions = source.actions;
    node.label = copy(source.label); node.value = copy(source.value); node.hint = copy(source.hint);
    describe(node);
    node.rect = {static_cast<int32_t>(std::floor(x + source.left * sx)), static_cast<int32_t>(std::floor(y + source.top * sy)),
                 static_cast<int32_t>(std::ceil(x + source.right * sx)), static_cast<int32_t>(std::ceil(y + source.bottom * sy))};
    next.push_back(std::move(node));
  }
  if (focused && std::none_of(next.begin(), next.end(), [](const Node& node){return node.id == focused;})) clearFocusLocked();
  nodes = std::move(next); root.children.clear();
  root.rect = {static_cast<int32_t>(x), static_cast<int32_t>(y), static_cast<int32_t>(x + width), static_cast<int32_t>(y + height)};
  for (auto& parent : nodes) for (const auto& child : nodes) if (child.parent == parent.id) parent.children.push_back(child.id);
  for (const auto& node : nodes) if (!node.parent) root.children.push_back(node.id);
  hash = tree.content_hash; lastWidth = width; lastHeight = height; lastX = x; lastY = y;
  events.push_back({root, ARKUI_ACCESSIBILITY_NATIVE_EVENT_TYPE_PAGE_CONTENT_UPDATE, false});
}
void hide() { std::lock_guard lock(mutex); clearFocusLocked(); nodes.clear(); root.children.clear(); hash.reset(); events.push_back({root, ARKUI_ACCESSIBILITY_NATIVE_EVENT_TYPE_PAGE_CONTENT_UPDATE, false}); }
void flush() {
  std::vector<Event> pending; ArkUI_AccessibilityProvider* target;
  { std::lock_guard lock(mutex); pending.swap(events); target = provider; }
  if (!target) return;
  for (const auto& item : pending) {
    auto* node = OH_ArkUI_CreateAccessibilityElementInfo(); auto* event = OH_ArkUI_CreateAccessibilityEventInfo();
    if (node && event) {
      fill(item.node, node, item.focused);
      OH_ArkUI_AccessibilityEventSetEventType(event, item.type);
      OH_ArkUI_AccessibilityEventSetElementInfo(event, node);
      OH_ArkUI_SendAccessibilityAsyncEvent(target, event, [](int32_t result) {
        if (result) OH_LOG_Print(LOG_APP, LOG_DEBUG, 0xD002D00, "PodJSA11y", "event result=%{public}d", result);
      });
    }
    if (event) OH_ArkUI_DestoryAccessibilityEventInfo(event);
    if (node) OH_ArkUI_DestoryAccessibilityElementInfo(node);
  }
}
}
