//! Read-only frame-committed accessibility export. No platform UI is owned here.
use super::*;

/// Queue one declared action from the committed semantic snapshot.
/// OK means accepted for guest delivery, not that its callback has completed.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_accessibility_action(
    runtime: *mut PodRuntime,
    node_id: i32,
    content_hash: u64,
    action: i32,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else { return ERR_ARGUMENT; };
    if !runtime.mounted { return ERR_STATE; }
    let name = match action {
        1 => "activate",
        2 => "increment",
        4 => "decrement",
        _ => return ERR_ARGUMENT,
    };
    if !runtime.surface.with_ui(|ui| {
        ui.accepts_accessibility_action(node_id, content_hash, action as u8)
    }) { return ERR_STATE; }
    runtime.bridge.borrow_mut().events.push_back(
        json!({"t":"accessibility.action","nodeId":node_id,"action":name}).to_string()
    );
    OK
}

#[repr(C)]
pub struct PodAccessibilitySnapshot {
    pub json: *const u8,
    pub byte_length: usize,
    pub content_hash: u64,
    pub frame_number: u64,
    pub changed: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct PodAccessibilityText {
    pub bytes: *const u8,
    pub byte_length: usize,
}
impl PodAccessibilityText {
    fn from_text(text: Option<&str>) -> Self {
        text.map_or(Self { bytes: std::ptr::null(), byte_length: 0 }, |text| Self {
            bytes: text.as_ptr(), byte_length: text.len(),
        })
    }
}

#[repr(C)]
pub struct PodAccessibilityTree {
    pub node_count: usize,
    pub content_hash: u64,
    pub frame_number: u64,
}

#[repr(C)]
pub struct PodAccessibilityNode {
    pub id: i32,
    pub parent_id: i32,
    pub role: i32,
    pub state: u16,
    pub actions: u8,
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
    pub label: PodAccessibilityText,
    pub value: PodAccessibilityText,
    pub hint: PodAccessibilityText,
}

/// Independent readers compare this hash themselves; no shared changed cursor.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_accessibility_tree(runtime: *mut PodRuntime, out: *mut PodAccessibilityTree) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else { return ERR_ARGUMENT; };
    if out.is_null() { return ERR_ARGUMENT; }
    if !runtime.mounted { return ERR_STATE; }
    runtime.surface.with_ui(|ui| {
        let snapshot = ui.current_accessibility();
        unsafe { *out = PodAccessibilityTree { node_count: snapshot.nodes.len(), content_hash: snapshot.content_hash, frame_number: snapshot.frame_number }; }
    });
    OK
}

/// Borrow a node from the committed tree. Does not allocate, serialize or draw.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_accessibility_node(runtime: *mut PodRuntime, index: usize, out: *mut PodAccessibilityNode) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else { return ERR_ARGUMENT; };
    if out.is_null() { return ERR_ARGUMENT; }
    if !runtime.mounted { return ERR_STATE; }
    runtime.surface.with_ui(|ui| {
        let Some(node) = ui.current_accessibility().nodes.get(index) else { return ERR_ARGUMENT; };
        unsafe { *out = PodAccessibilityNode {
            id: node.id, parent_id: node.parent_id, role: node.role as i32,
            state: node.state, actions: node.actions,
            left: node.bounds.left, top: node.bounds.top, right: node.bounds.right, bottom: node.bounds.bottom,
            label: PodAccessibilityText::from_text(Some(&node.label)),
            value: PodAccessibilityText::from_text(node.value.as_deref()),
            hint: PodAccessibilityText::from_text(node.hint.as_deref()),
        }; }
        OK
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_set_accessibility_enabled(
    runtime: *mut PodRuntime,
    enabled: i32,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if enabled != 0 && enabled != 1 {
        return ERR_ARGUMENT;
    }
    runtime
        .surface
        .with_ui(|ui| ui.set_accessibility_enabled(enabled != 0));
    // Re-enabled hosts may have discarded their projection even when the next
    // committed tree is byte-identical to the previous enabled session.
    runtime.accessibility_hash = None;
    OK
}

/// Bytes remain valid until the next accessibility snapshot query or destruction.
/// Does not draw or apply pending mutations. Call after pod_runtime_snapshot.
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_accessibility_snapshot(
    runtime: *mut PodRuntime,
    out: *mut PodAccessibilitySnapshot,
) -> i32 {
    let Ok(runtime) = runtime_mut(runtime) else {
        return ERR_ARGUMENT;
    };
    if out.is_null() {
        return ERR_ARGUMENT;
    }
    if !runtime.mounted {
        return ERR_STATE;
    }
    let (hash, frame_number) = runtime.surface.with_ui(|ui| {
        let snapshot = ui.current_accessibility();
        (snapshot.content_hash, snapshot.frame_number)
    });
    let changed = Some(hash) != runtime.accessibility_hash;
    if changed {
        let value = runtime.surface.with_ui(|ui| {
            let nodes: Vec<Value> = ui.current_accessibility().nodes.iter().map(|node| {
                use pocketjs_core::accessibility::Role;
                let role = match node.role {
                    Role::Text=>"text",Role::Button=>"button",Role::Image=>"image",Role::Header=>"header",Role::Link=>"link",
                    Role::Checkbox=>"checkbox",Role::Switch=>"switch",Role::Adjustable=>"adjustable",Role::List=>"list",Role::ListItem=>"listitem",
                };
                json!({"id":node.id,"parentId":node.parent_id,"role":role,"label":node.label,"value":node.value,"hint":node.hint,
                    "state":node.state,"actions":node.actions,"bounds":{"left":node.bounds.left,"top":node.bounds.top,"right":node.bounds.right,"bottom":node.bounds.bottom}})
            }).collect();
            json!({"schema":1,"nodes":nodes})
        });
        match serde_json::to_vec(&value) {
            Ok(bytes) => runtime.accessibility_json = bytes,
            Err(error) => {
                set_error(format!("Accessibility serialization failed: {error}"));
                return ERR_STATE;
            }
        }
        runtime.accessibility_hash = Some(hash);
    }
    unsafe {
        *out = PodAccessibilitySnapshot {
            json: runtime.accessibility_json.as_ptr(),
            byte_length: runtime.accessibility_json.len(),
            content_hash: hash,
            frame_number,
            changed: i32::from(changed),
        };
    }
    OK
}
