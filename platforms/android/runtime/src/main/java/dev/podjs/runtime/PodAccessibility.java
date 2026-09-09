package dev.podjs.runtime;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/** UI-thread projection of a committed primary semantic tree. */
final class PodAccessibility extends AccessibilityNodeProvider {
    interface Actions { boolean send(int id, long hash, int action); }
    private final View view;
    private final Actions actions;
    private final AccessibilityManager manager;
    private LinkedHashMap<Integer, JSONObject> nodes = new LinkedHashMap<>();
    private long hash;
    private int logicalWidth = 1, logicalHeight = 1;
    private int focused = HOST_VIEW_ID, hovered = HOST_VIEW_ID;
    PodAccessibility(View view, Actions actions) {
        this.view = view; this.actions = actions;
        manager = (AccessibilityManager)view.getContext().getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
    }
    boolean enabled() { return manager != null && manager.isEnabled(); }
    void clear() {
        if (focused != HOST_VIEW_ID) emit(focused, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
        nodes.clear(); focused = hovered = HOST_VIEW_ID;
    }
    void update(byte[] bytes, long[] metadata) {
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (root.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported semantic schema");
            JSONArray list = root.getJSONArray("nodes");
            LinkedHashMap<Integer, JSONObject> next = new LinkedHashMap<>();
            for (int i = 0; i < list.length(); i++) { JSONObject node = list.getJSONObject(i); next.put(node.getInt("id"), node); }
            if (focused != HOST_VIEW_ID && !next.containsKey(focused)) {
                emit(focused, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED); focused = HOST_VIEW_ID;
            }
            nodes = next; hash = metadata[0]; logicalWidth = Math.max(1, (int)metadata[1]); logicalHeight = Math.max(1, (int)metadata[2]);
            if (!nodes.containsKey(hovered)) hovered = HOST_VIEW_ID;
            emit(HOST_VIEW_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        } catch (Exception error) { clear(); android.util.Log.e("PodJS", "Invalid semantic snapshot", error); }
    }
    private Rect bounds(JSONObject node) {
        JSONObject b = node.optJSONObject("bounds");
        if (b == null) return new Rect();
        float sx = (float)view.getWidth()/logicalWidth, sy = (float)view.getHeight()/logicalHeight;
        return new Rect((int)Math.floor(b.optInt("left")*sx),(int)Math.floor(b.optInt("top")*sy),
            (int)Math.ceil(b.optInt("right")*sx),(int)Math.ceil(b.optInt("bottom")*sy));
    }
    @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
        if (id == HOST_VIEW_ID) {
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(view);
            view.onInitializeAccessibilityNodeInfo(info);
            for (JSONObject node : nodes.values()) if (node.optInt("parentId") == 0) info.addChild(view, node.optInt("id"));
            return info;
        }
        JSONObject node = nodes.get(id); if (node == null) return null;
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.setSource(view, id); info.setPackageName(view.getContext().getPackageName());
        int parent = node.optInt("parentId");
        if (parent == 0) info.setParent(view); else info.setParent(view, parent);
        for (JSONObject child : nodes.values()) if (child.optInt("parentId") == id) info.addChild(view, child.optInt("id"));
        String role = node.optString("role");
        String className = "android.view.View";
        switch (role) {
            case "text": case "header": case "link": className="android.widget.TextView"; break;
            case "button": className="android.widget.Button"; break;
            case "image": className="android.widget.ImageView"; break;
            case "checkbox": className="android.widget.CheckBox"; break;
            case "switch": className="android.widget.Switch"; break;
            case "adjustable": className="android.widget.SeekBar"; break;
            case "list": className="android.widget.ListView"; break;
        }
        info.setClassName(className); info.setHeading("header".equals(role));
        info.setContentDescription(node.optString("label", ""));
        if (!node.isNull("hint")) info.setHintText(node.optString("hint"));
        int state = node.optInt("state"), mask = node.optInt("actions");
        info.setEnabled((state & 1)==0); info.setSelected((state & 2)!=0);
        info.setCheckable((state & 64)!=0); info.setChecked((state & 4)!=0);
        // An explicit value must not hide independent mixed/expanded/busy facts.
        java.util.ArrayList<String> descriptions = new java.util.ArrayList<>();
        if (!node.isNull("value") && !node.optString("value").isEmpty()) descriptions.add(node.optString("value"));
        if ((state & 8)!=0) descriptions.add(view.getContext().getString(R.string.pod_accessibility_mixed));
        else if ((state & 64)!=0) descriptions.add(view.getContext().getString((state & 4)!=0 ? R.string.pod_accessibility_checked : R.string.pod_accessibility_unchecked));
        if ((state & 128)!=0) descriptions.add(view.getContext().getString((state & 16)!=0 ? R.string.pod_accessibility_expanded : R.string.pod_accessibility_collapsed));
        if ((state & 32)!=0) descriptions.add(view.getContext().getString(R.string.pod_accessibility_busy));
        if (!descriptions.isEmpty()) info.setStateDescription(android.text.TextUtils.join(", ", descriptions));
        info.setFocusable(true); info.setScreenReaderFocusable(true); info.setVisibleToUser(view.isShown());
        info.setAccessibilityFocused(focused == id);
        info.addAction(focused == id ? AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS : AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        info.setClickable((mask & 1)!=0);
        if ((mask & 1)!=0) info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
        if ((mask & 2)!=0) info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if ((mask & 4)!=0) info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        Rect local = bounds(node), relative = new Rect(local);
        if (nodes.containsKey(parent)) { Rect p = bounds(nodes.get(parent)); relative.offset(-p.left, -p.top); }
        info.setBoundsInParent(relative);
        int[] location = new int[2]; view.getLocationOnScreen(location); local.offset(location[0], location[1]); info.setBoundsInScreen(local);
        int previous = HOST_VIEW_ID;
        for (int candidate : nodes.keySet()) { if(candidate == id)break; previous=candidate; }
        if(previous != HOST_VIEW_ID)info.setTraversalAfter(view, previous);
        return info;
    }
    @Override public AccessibilityNodeInfo findFocus(int focus) {
        return focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && focused != HOST_VIEW_ID ? createAccessibilityNodeInfo(focused) : null;
    }
    @Override public boolean performAction(int id, int action, Bundle args) {
        JSONObject node=nodes.get(id); if(node==null)return false;
        if(action==AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
            if(focused==id)return false;
            if(focused!=HOST_VIEW_ID)emit(focused,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
            focused=id;emit(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);view.invalidate();return true;
        }
        if(action==AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
            if(focused!=id)return false;focused=HOST_VIEW_ID;emit(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);view.invalidate();return true;
        }
        int bit=action==AccessibilityNodeInfo.ACTION_CLICK?1:action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?2:action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD?4:0;
        return bit!=0&&(node.optInt("state")&1)==0&&(node.optInt("actions")&bit)!=0&&actions.send(id,hash,bit);
    }
    boolean hover(MotionEvent event) {
        if(!enabled()||!manager.isTouchExplorationEnabled())return false;
        boolean hadHover=hovered!=HOST_VIEW_ID;
        int next=HOST_VIEW_ID;
        if(event.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT) {
            for(JSONObject node:nodes.values())if(bounds(node).contains((int)event.getX(),(int)event.getY()))next=node.optInt("id");
        }
        if(next!=hovered) {
            if(next!=HOST_VIEW_ID)emit(next,AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
            if(hovered!=HOST_VIEW_ID)emit(hovered,AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
            hovered=next;
        }
        return next!=HOST_VIEW_ID||hadHover;
    }
    private void emit(int id,int type) {
        if(!enabled()||view.getParent()==null)return;
        AccessibilityEvent event=AccessibilityEvent.obtain(type);event.setSource(view,id);
        event.setPackageName(view.getContext().getPackageName());event.setClassName("android.view.View");
        JSONObject node=nodes.get(id);if(node!=null)event.setContentDescription(node.optString("label"));
        if(type==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        view.getParent().requestSendAccessibilityEvent(view,event);
    }
}
