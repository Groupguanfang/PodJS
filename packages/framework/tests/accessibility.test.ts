import { expect, test } from "bun:test";
import { render } from "@pocketjs/framework";
import { createElement, insertNode, setProp, type NodeMirror } from "@pocketjs/framework/renderer";
import type { HostOps } from "@pocketjs/framework/host";
import { __pumpPodEvents } from "../src/watch.ts";

test("mounted SDK event pump invokes exact controls and rechecks state between queued actions", () => {
  const previousPod = (globalThis as { pod?: unknown }).pod;
  const previousFrame = (globalThis as { frame?: unknown }).frame;
  let batch: string | undefined;
  (globalThis as { pod?: unknown }).pod = { takeEvents: () => { const result=batch; batch=undefined; return result; } };
  let nextId = 2;
  const noop = () => {};
  const ops: HostOps = {
    createNode: () => nextId++, destroyNode: noop, insertBefore: noop, removeChild: noop,
    setStyle: noop, setProp: noop, setText: noop, replaceText: noop,
    setAccessibility: () => true, uploadTexture: () => 1, setImage: noop, setSprite: noop,
    animate: () => 1, cancelAnim: noop, setFocus: noop, setActive: noop,
    loadStyles: noop, loadFontAtlas: noop, measureText: () => 0,
  };
  let control!: NodeMirror;
  const calls: string[] = [];
  let dispose: (() => void) | undefined;
  try {
    dispose = render(() => {
      const parent=createElement("view");
      control=createElement("view");
      setProp(parent,"onPress",()=>calls.push("wrong ancestor"));
      setProp(control,"accessibilityActions",["increment"]);
      setProp(control,"onAccessibilityAction",(event: {actionName:string})=> {
        calls.push(event.actionName);
        setProp(control,"accessibilityState",{disabled:true});
      });
      insertNode(parent,control);
      return parent;
    }, {ops});
    batch=JSON.stringify([
      {t:"accessibility.action",nodeId:control.id,action:"activate"},
      {t:"accessibility.action",nodeId:control.id,action:"increment"},
      {t:"accessibility.action",nodeId:control.id,action:"increment"},
    ]);
    // Exercise the registered frame pump, not a direct input dispatcher call.
    (globalThis as {frame?: (buttons:number)=>void}).frame!(0);
    expect(calls).toEqual(["increment"]);
    expect(batch).toBeUndefined();
    setProp(control,"accessibilityState",undefined,control.domAttrs?.accessibilityState);
    setProp(control,"onAccessibilityAction",undefined,control.domAttrs?.onAccessibilityAction);
    setProp(control,"onPress",()=>calls.push("press"));
    batch=JSON.stringify([{t:"accessibility.action",nodeId:control.id,action:"activate"}]);
    __pumpPodEvents();
    expect(calls).toEqual(["increment","press"]);
    dispose(); dispose=undefined;
    batch=JSON.stringify([{t:"accessibility.action",nodeId:control.id,action:"activate"}]);
    __pumpPodEvents();
    expect(calls).toEqual(["increment","press"]);
  } finally {
    dispose?.();
    (globalThis as {pod?:unknown}).pod=previousPod;
    (globalThis as {frame?:unknown}).frame=previousFrame;
  }
});
