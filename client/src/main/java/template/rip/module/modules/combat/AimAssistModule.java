package template.rip.module.modules.combat;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.util.Pair;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import template.rip.Template;
import template.rip.api.event.events.MouseDeltaEvent;
import template.rip.api.event.events.MouseUpdateEvent;
import template.rip.api.event.orbit.EventHandler;
import template.rip.api.object.Description;
import template.rip.api.rotation.Rotation;
import template.rip.api.rotation.RotationUtils;
import template.rip.api.util.KeyUtils;
import template.rip.api.util.MathUtils;
import template.rip.api.util.PlayerUtils;
import template.rip.module.Module;
import template.rip.module.modules.player.CombatBotModule;
import template.rip.module.setting.settings.*;

public class AimAssistModule extends Module {

    public enum modeHEnum{Normal, Assist_Only, Off}
    public final ModeSetting<modeHEnum> modeH = new ModeSetting<>(this, modeHEnum.Normal, "Horizontal");
    public final MinMaxNumberSetting yawSpeed = new MinMaxNumberSetting(this, 4, 8, 0.1, 10d, 0.1, "Horizontal Speeds");

    public enum modeVEnum{Normal, Assist_Only, Off}
    public final ModeSetting<modeVEnum> modeV = new ModeSetting<>(this, modeVEnum.Normal, "Vertical");
    public final MinMaxNumberSetting pitchSpeed = new MinMaxNumberSetting(this, 4, 8, 0.1, 10d, 0.1, "Vertical Speeds");

    public final BooleanSetting holdLeftClick = new BooleanSetting(this, Description.of("Requires you to hold left click to use."), false, "Hold Left Click").setAdvanced();
    public final BooleanSetting minimalPitch = new BooleanSetting(this, Description.of("Only changes pitch if you're not facing the target's box"), true, "Minimal Pitch movement").setAdvanced();

    public final DividerSetting rotationSettings = new DividerSetting(this, true, Description.of("Additional advanced settings regarding to rotation and targetting"), "Rotation settings (adv)");

    public enum modeEnum{Normal, Silent}
    public final ModeSetting<modeEnum> mode = new ModeSetting<>(this, modeEnum.Normal, "Rotation Mode");

    public enum targetModeEnum{Optimal, Center}
    public final ModeSetting<targetModeEnum> targetMode = new ModeSetting<>(this, targetModeEnum.Optimal, "Target Pos Mode");

    public final NumberSetting randomRotAmount = new NumberSetting(this, 0.5, 0.0, 5d, 0.1, "Camera shake amount").setAdvanced();
    public final NumberSetting boxScale = new NumberSetting(this, 0.05, 0.0, 1d, 0.01, "Target box shrink").setAdvanced();
    public final NumberSetting prediction = new NumberSetting(this, 1, 0.0, 3d, 0.01, "Prediction amount").setAdvanced();

    public enum speedModeEnum{Slow, Normal}
    public final ModeSetting<speedModeEnum> speedMode = new ModeSetting<>(this, speedModeEnum.Slow, "Aim Speed Mode");

    public final BooleanSetting maxDistance = new BooleanSetting(this, true, "Max Aim Distance");
    public final NumberSetting distance = new NumberSetting(this, 8d, 3d, 16d, 0.1d, "Aim Distance");
    public final NumberSetting fov = new NumberSetting(this, 180d, 1d, 360d, 1d, "FOV");

    public final BooleanSetting onlyWeapon = new BooleanSetting(this, true, "Only Weapon");
    public final BooleanSetting disableInScreens = new BooleanSetting(this, true, "Disable in screens").setAdvanced();

    public Box lastBox = null;
    public Vec3d lastTargetPos;

    private double mouseX = 0;
    private double mouseY = 0;

    // NEW smoothing memory
    private float smoothYaw;
    private float smoothPitch;

    public AimAssistModule(Category category, Description description, String name) {
        super(category, description, name);
        rotationSettings.addSetting(mode, targetMode, randomRotAmount, boxScale, prediction, speedMode);
    }

    @Override
    public void onEnable() {
        mouseX = 0;
        mouseY = 0;
        lastBox = null;
        lastTargetPos = null;

        if (mc.player != null) {
            smoothYaw = mc.player.getYaw();
            smoothPitch = mc.player.getPitch();
        }
    }

    private boolean isHoldingWeapon() {
        if (mc.player == null) return false;
        ItemStack heldItem = mc.player.getMainHandStack();
        return heldItem.getItem() instanceof SwordItem || heldItem.getItem() instanceof AxeItem;
    }

    @EventHandler
    public void mouseDelta(MouseDeltaEvent event) {
        mouseX = MathUtils.coerceIn(mouseX + event.deltaX * 0.5, -5, 5);
        mouseY = MathUtils.coerceIn(mouseY + event.deltaY * 0.5, -5, 5);
    }

    @EventHandler
    public void onMouseUpdate(MouseUpdateEvent.Post event) {

        if ((mc.currentScreen == null || !disableInScreens.isEnabled()) &&
                (!holdLeftClick.isEnabled() || KeyUtils.isKeyPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT))) {

            LivingEntity target = PlayerUtils.findFirstLivingTargetOrNull();

            if (target == null || !nullCheck())
                return;

            if (maxDistance.isEnabled() &&
                    MathUtils.closestPosBoxDistance(mc.player.getPos(), target.getBoundingBox()) > distance.value)
                return;

            if (!isHoldingWeapon() && onlyWeapon.isEnabled())
                return;

            lastTargetPos = getTargetPos(
                    PlayerUtils.renderBox(target)
                            .contract(boxScale.value)
                            .offset(target.getPos()
                                    .subtract(PlayerUtils.lastPosVec(target))
                                    .multiply(prediction.value))
            );

            if (lastTargetPos == null)
                return;

            Rotation targetRot = RotationUtils.getRotations(mc.player.getEyePos(), lastTargetPos);

            if (RotationUtils.getAngleToRotation(targetRot) > fov.getValue() / 2)
                return;

            double yawSpeedVal = yawSpeed.getRandomDouble();
            double pitchSpeedVal = pitchSpeed.getRandomDouble();

            double speedDiv = speedMode.is(speedModeEnum.Normal) ? 120.0 : 300.0;

            float targetYaw = (float) targetRot.fyaw();
            float targetPitch = (float) targetRot.fpitch();

            // SMOOTH INTERPOLATION
            smoothYaw = MathHelper.lerpAngleDegrees((float)(yawSpeedVal / speedDiv), smoothYaw, targetYaw);
            smoothPitch = MathHelper.lerpAngleDegrees((float)(pitchSpeedVal / speedDiv), smoothPitch, targetPitch);

            // LIGHT RANDOMNESS (smooth, not jitter)
            double r = randomRotAmount.value / 50.0;
            smoothYaw += MathUtils.getRandomDouble(-r, r);
            smoothPitch = MathUtils.coerceIn(smoothPitch + MathUtils.getRandomDouble(-r, r), -90, 90);

            Rotation finalRot = new Rotation(smoothYaw, smoothPitch);
            finalRot = RotationUtils.correctSensitivity(finalRot);

            if (mode.is(modeEnum.Normal)) {
                Pair<Double, Double> pair = RotationUtils.approximateRawCursorDeltas(
                        RotationUtils.closestDelta(finalRot, RotationUtils.entityRotation(mc.player))
                );
                mc.player.changeLookDirection(pair.getLeft(), pair.getRight());
            } else {
                Template.rotationManager().setRotation(finalRot);
            }
        }
    }

    public Vec3d getTargetPos(Box box) {
        lastBox = box;

        ClientPlayerEntity pl = mc.player;
        Vec3d start = pl.getEyePos();
        Vec3d close = MathUtils.closestPointToBox(start, box);

        if (box.contains(start))
            return box.getCenter();

        return switch (targetMode.getMode()) {
            case Optimal -> close;
            case Center -> box.getCenter();
        };
    }
}