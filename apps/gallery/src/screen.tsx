import { createSignal } from "solid-js";
import { Text, View } from "@pocketjs/framework/components";
import { animate } from "@pocketjs/framework/animation";
import { VirtualList, type VirtualListHandle } from "@pocketjs/framework/virtual-list";
import type { NodeMirror } from "@pocketjs/framework/renderer";
import {
  RelativeAxis,
  RelativeAxisUnits,
  getDisplayMetrics,
  haptics,
  kv,
  onAxisDelta,
  onLifecycleChange,
  onSystemThemeChange,
} from "@podjs/framework/watch";

const ROWS = 1_000;
const ROW_HEIGHT = 32;
const WHEEL_DEGREES_PER_ROW = 12;
const WHEEL_INERTIA_MS = 120;

export default function Gallery() {
  const metrics = getDisplayMetrics();
  const [selected, setSelected] = createSignal(kv.get<number>("selected") ?? 0);
  const [theme, setTheme] = createSignal("dark");
  const [state, setState] = createSignal("active");
  let pulse: NodeMirror | undefined;
  let list: VirtualListHandle | undefined;

  onLifecycleChange(setState);
  onSystemThemeChange(setTheme);
  onAxisDelta(RelativeAxis.Primary, delta => {
    // The crown scrolls content without changing selection. Re-targeting the
    // ease-out tween accumulates wheel input, then coasts smoothly to rest.
    list?.scroller.scrollBy(
      -delta * ROW_HEIGHT / (WHEEL_DEGREES_PER_ROW * RelativeAxisUnits.PerDegree),
      { durMs: WHEEL_INERTIA_MS },
    );
  });

  function activate(index: number) {
    setSelected(index);
    kv.set("selected", index);
    haptics.perform("click");
    if (pulse) animate(pulse, "scale", 1.18, { dur: 90, easing: "out" });
  }

  return (
    <View class="relative w-[240] h-[240] overflow-hidden bg-[#070a12]">
      <View class="absolute left-[18] right-[18] top-[12] h-[46] rounded-[14] bg-gradient-to-r from-[#243b68] to-[#472b68]">
        <Text class="absolute left-[12] top-[7] text-lg text-white font-bold">PodJS 四端手表</Text>
        <Text class="absolute left-[12] top-[28] text-xs text-[#a9badc]">
          {metrics.physicalWidth}×{metrics.physicalHeight} · {state()} · {theme()}
        </Text>
      </View>

      <View class="absolute left-[18] right-[18] top-[66] h-[142] rounded-[16] overflow-hidden bg-[#101827]">
        <VirtualList
          ref={value => { list = value; }}
          count={ROWS}
          rowHeight={ROW_HEIGHT}
          height={142}
          overscan={64}
          focusRows={false}
          onRowPress={activate}
          renderRow={index => (
            <View
              class={index === selected()
                ? "relative w-full h-[32] bg-[#3157a4]"
                : "relative w-full h-[32] bg-[#101827]"}
            >
              <Text class="absolute left-[12] top-[8] text-sm text-white">性能行 {index + 1}</Text>
              <View class="absolute right-[12] top-[13] w-[28] h-[6] rounded-full bg-[#45d6a7]" />
            </View>
          )}
        />
      </View>

      <View
        ref={pulse}
        onPress={() => activate(selected())}
        focusable
        class="absolute left-[72] top-[216] w-[96] h-[20] items-center justify-center rounded-[10] bg-[#45d6a7] active:bg-[#2ca27f]"
      >
        <Text class="text-xs text-[#07130f] font-bold">触控 / 表冠</Text>
      </View>
    </View>
  );
}
