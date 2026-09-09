// Build with the real native_interface_accessibility.h and hilog/log.h headers.
// OS objects and the already-tested committed C ABI are test doubles here.
#include "accessibility_provider.h"
#include <arkui/native_interface_accessibility.h>
#include <hilog/log.h>
#include <cassert>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

struct ArkUI_AccessibilityElementInfo {
  int32_t id=0,parent=0;
  bool enabled=false,focused=false,selected=false,checked=false;
  std::string label,role,description;
  ArkUI_AccessibleRect rect{};
  std::vector<int64_t> children;
};
struct ArkUI_AccessibilityElementInfoList { std::vector<std::unique_ptr<ArkUI_AccessibilityElementInfo>> items; };
struct ArkUI_AccessibilityEventInfo { ArkUI_AccessibilityEventType type{}; };
struct ArkUI_AccessibilityProvider {};
static ArkUI_AccessibilityProviderCallbacks* callbacks;
static int liveObjects=0;
static std::vector<ArkUI_AccessibilityEventType> sentEvents;
static std::vector<PodAccessibilityNode> source;
static uint64_t sourceHash=0xfedcba9876543210ULL;

extern "C" {
int OH_LOG_Print(LogType, LogLevel, unsigned int, const char*, const char*, ...) { return 0; }
int32_t OH_ArkUI_AccessibilityProviderRegisterCallback(ArkUI_AccessibilityProvider*,ArkUI_AccessibilityProviderCallbacks* value){callbacks=value;return 0;}
ArkUI_AccessibilityElementInfo* OH_ArkUI_AddAndGetAccessibilityElementInfo(ArkUI_AccessibilityElementInfoList* list){list->items.push_back(std::make_unique<ArkUI_AccessibilityElementInfo>());return list->items.back().get();}
ArkUI_AccessibilityElementInfo* OH_ArkUI_CreateAccessibilityElementInfo(){++liveObjects;return new ArkUI_AccessibilityElementInfo;}
ArkUI_AccessibilityEventInfo* OH_ArkUI_CreateAccessibilityEventInfo(){++liveObjects;return new ArkUI_AccessibilityEventInfo;}
void OH_ArkUI_DestoryAccessibilityElementInfo(ArkUI_AccessibilityElementInfo* p){--liveObjects;delete p;}
void OH_ArkUI_DestoryAccessibilityEventInfo(ArkUI_AccessibilityEventInfo* p){--liveObjects;delete p;}
int32_t OH_ArkUI_AccessibilityEventSetEventType(ArkUI_AccessibilityEventInfo* p,ArkUI_AccessibilityEventType type){p->type=type;return 0;}
int32_t OH_ArkUI_AccessibilityEventSetElementInfo(ArkUI_AccessibilityEventInfo*,ArkUI_AccessibilityElementInfo*){return 0;}
void OH_ArkUI_SendAccessibilityAsyncEvent(ArkUI_AccessibilityProvider*,ArkUI_AccessibilityEventInfo* event,void(*done)(int32_t)){
  // Reentrant query proves events are not sent while the projection mutex is held.
  ArkUI_AccessibilityElementInfoList list;
  assert(callbacks->findAccessibilityNodeInfosById(0,ARKUI_ACCESSIBILITY_NATIVE_SEARCH_MODE_PREFETCH_CURRENT,99,&list)==0);
  sentEvents.push_back(event->type);done(0);
}
#define SET_FIELD(Name, Type, field) int32_t OH_ArkUI_AccessibilityElementInfoSet##Name(ArkUI_AccessibilityElementInfo* node,Type value){node->field=value;return 0;}
SET_FIELD(ElementId,int32_t,id)
SET_FIELD(ParentId,int32_t,parent)
SET_FIELD(ComponentType,const char*,role)
SET_FIELD(AccessibilityText,const char*,label)
SET_FIELD(AccessibilityDescription,const char*,description)
SET_FIELD(Enabled,bool,enabled)
SET_FIELD(Selected,bool,selected)
SET_FIELD(Checked,bool,checked)
SET_FIELD(AccessibilityFocused,bool,focused)
#define IGNORE_FIELD(Name, Type) int32_t OH_ArkUI_AccessibilityElementInfoSet##Name(ArkUI_AccessibilityElementInfo*,Type){return 0;}
IGNORE_FIELD(AccessibilityLevel,const char*)
IGNORE_FIELD(Contents,const char*)
IGNORE_FIELD(HintText,const char*)
IGNORE_FIELD(Visible,bool)
IGNORE_FIELD(Checkable,bool)
IGNORE_FIELD(Focusable,bool)
IGNORE_FIELD(Clickable,bool)
IGNORE_FIELD(Scrollable,bool)
int32_t OH_ArkUI_AccessibilityElementInfoSetScreenRect(ArkUI_AccessibilityElementInfo* node,ArkUI_AccessibleRect* rect){node->rect=*rect;return 0;}
int32_t OH_ArkUI_AccessibilityElementInfoSetChildNodeIds(ArkUI_AccessibilityElementInfo* node,int32_t count,int64_t* ids){if(count)node->children.assign(ids,ids+count);else node->children.clear();return 0;}
int32_t OH_ArkUI_AccessibilityElementInfoSetOperationActions(ArkUI_AccessibilityElementInfo*,int32_t,ArkUI_AccessibleAction*){return 0;}
int32_t pod_runtime_accessibility_tree(PodRuntime*,PodAccessibilityTree* out){*out={source.size(),sourceHash,1};return 0;}
int32_t pod_runtime_accessibility_node(PodRuntime*,size_t index,PodAccessibilityNode* out){if(index>=source.size())return -1;*out=source[index];return 0;}
uint32_t pod_runtime_logical_width(const PodRuntime*){return 200;}
uint32_t pod_runtime_logical_height(const PodRuntime*){return 100;}
}
static PodAccessibilityNode make(int32_t id,int32_t parent,int role,uint16_t state,uint8_t actions,const std::string& text){
  return {id,parent,role,state,actions,10,20,70,60,{reinterpret_cast<const uint8_t*>(text.data()),text.size()},{nullptr,0},{nullptr,0}};
}
int main(){
  ArkUI_AccessibilityProvider os;
  int calls=0; uint64_t actionHash=0; int32_t actionId=0; uint8_t actionBit=0;
  assert(pod_a11y::bind(&os,[&](int32_t id,uint64_t hash,uint8_t bit){
    ArkUI_AccessibilityElementInfo info;
    assert(callbacks->findNextFocusAccessibilityNode(0,ARKUI_ACCESSIBILITY_NATIVE_DIRECTION_FORWARD,7,&info)==0);
    ++calls;actionHash=hash;actionId=id;actionBit=bit;return true;
  }));
  std::string parent="Parent😀", child="Child😀", peer="Disabled";
  source={make(100002,0,1,0,1,parent),make(9,100002,7,2,6,child),make(50000,0,5,69,0,peer)};
  pod_a11y::commit(nullptr,400,200,5,7);pod_a11y::flush();assert(sentEvents.size()==1);assert(liveObjects==0);
  parent="CHANGED SOURCE"; // Provider owns copied UTF-8, not C ABI borrowed pointers.
  ArkUI_AccessibilityElementInfoList all;
  assert(callbacks->findAccessibilityNodeInfosById(-1,ARKUI_ACCESSIBILITY_NATIVE_SEARCH_MODE_PREFETCH_RECURSIVE_CHILDREN,1,&all)==0);
  assert(all.items.size()==4);assert(all.items[0]->parent==-2100000);
  assert(all.items[1]->label=="Parent😀");assert(all.items[2]->id==9);
  assert(all.items[1]->rect.leftTopX==25&&all.items[1]->rect.leftTopY==47&&all.items[1]->rect.rightBottomX==145);
  assert(all.items[1]->children==std::vector<int64_t>{9});assert(!all.items[3]->enabled&&all.items[3]->checked);
  ArkUI_AccessibilityElementInfoList search;
  assert(callbacks->findAccessibilityNodeInfosByText(100002,"😀",2,&search)==0);assert(search.items.size()==2);
  ArkUI_AccessibilityElementInfo next;
  assert(callbacks->findNextFocusAccessibilityNode(100002,ARKUI_ACCESSIBILITY_NATIVE_DIRECTION_FORWARD,3,&next)==0&&next.id==9);
  assert(callbacks->findNextFocusAccessibilityNode(0,ARKUI_ACCESSIBILITY_NATIVE_DIRECTION_BACKWARD,3,&next)==0&&next.id==50000);
  assert(callbacks->findNextFocusAccessibilityNode(50000,ARKUI_ACCESSIBILITY_NATIVE_DIRECTION_FORWARD,3,&next)!=0);
  assert(callbacks->executeAccessibilityAction(9,ARKUI_ACCESSIBILITY_NATIVE_ACTION_TYPE_SCROLL_FORWARD,nullptr,4)==0);
  assert(calls==1&&actionId==9&&actionBit==2&&actionHash==sourceHash);
  assert(callbacks->executeAccessibilityAction(9,ARKUI_ACCESSIBILITY_NATIVE_ACTION_TYPE_CLICK,nullptr,4)!=0);
  assert(callbacks->executeAccessibilityAction(50000,ARKUI_ACCESSIBILITY_NATIVE_ACTION_TYPE_CLICK,nullptr,4)!=0);
  assert(callbacks->executeAccessibilityAction(9,ARKUI_ACCESSIBILITY_NATIVE_ACTION_TYPE_GAIN_ACCESSIBILITY_FOCUS,nullptr,5)==0);
  assert(callbacks->findFocusedAccessibilityNode(100002,ARKUI_ACCESSIBILITY_NATIVE_FOCUS_TYPE_ACCESSIBILITY,6,&next)==0&&next.id==9);
  size_t eventCount=sentEvents.size();pod_a11y::commit(nullptr,400,200,5,7);pod_a11y::flush();assert(sentEvents.size()==eventCount);
  source.erase(source.begin()+1);sourceHash++;
  source[0]=make(100002,0,1,0,1,parent);
  pod_a11y::commit(nullptr,400,200,5,7);pod_a11y::flush();
  assert(sentEvents.size()==eventCount+2);assert(callbacks->findFocusedAccessibilityNode(0,ARKUI_ACCESSIBILITY_NATIVE_FOCUS_TYPE_ACCESSIBILITY,8,&next)!=0);
  assert(callbacks->executeAccessibilityAction(9,ARKUI_ACCESSIBILITY_NATIVE_ACTION_TYPE_SCROLL_FORWARD,nullptr,4)!=0);
  std::string nulLabel("A\0B",3), value="50%";
  source={make(100002,0,5,248,0,nulLabel)};
  source[0].value={reinterpret_cast<const uint8_t*>(value.data()),value.size()};sourceHash++;
  pod_a11y::commit(nullptr,400,200,5,7);pod_a11y::flush();
  ArkUI_AccessibilityElementInfoList described;
  assert(callbacks->findAccessibilityNodeInfosById(100002,ARKUI_ACCESSIBILITY_NATIVE_SEARCH_MODE_PREFETCH_CURRENT,10,&described)==0);
  assert(described.items[0]->label=="A\xef\xbf\xbd" "B");
  assert(described.items[0]->description=="50%, Partially checked, Expanded, Busy");
  const std::array<std::string,6> labels{"已选中","未选中","部分选中","已展开","已收起","正忙"};
  pod_a11y::setStateLabels(labels);pod_a11y::flush();
  ArkUI_AccessibilityElementInfoList localized;
  assert(callbacks->findAccessibilityNodeInfosById(100002,ARKUI_ACCESSIBILITY_NATIVE_SEARCH_MODE_PREFETCH_CURRENT,11,&localized)==0);
  assert(localized.items[0]->description=="50%, 部分选中, 已展开, 正忙");
  eventCount=sentEvents.size();pod_a11y::setStateLabels(labels);pod_a11y::flush();assert(sentEvents.size()==eventCount);
  pod_a11y::hide();pod_a11y::flush();assert(liveObjects==0);
  assert(callbacks->findNextFocusAccessibilityNode(0,ARKUI_ACCESSIBILITY_NATIVE_DIRECTION_FORWARD,9,&next)!=0);
  std::cout<<"Harmony accessibility provider contracts passed\n";
}
