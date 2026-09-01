import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
export default class EntryAbility extends UIAbility {
  onCreate(_want: Want, _params: AbilityConstant.LaunchParam): void {}
  onWindowStageCreate(stage: window.WindowStage): void { stage.loadContent('pages/Index'); }
}
