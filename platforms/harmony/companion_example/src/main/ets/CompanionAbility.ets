import { UIAbility } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { closeExampleConnection } from './ExampleClient';
import { cancelExampleForeground } from './ExampleForeground';
export default class CompanionAbility extends UIAbility {
  onWindowStageCreate(stage: window.WindowStage): void { stage.loadContent('pages/Index'); }
  onBackground(): void { cancelExampleForeground(); closeExampleConnection(); }
  onDestroy(): void { cancelExampleForeground(); closeExampleConnection(); }
}
