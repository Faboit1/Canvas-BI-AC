package io.canvasmc.canvas.anticheat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Hides the exact health and absorption of players from everyone but themselves, see
 * {@link io.canvasmc.canvas.WorldConfig.Anticheat#obfuscatePlayerHealth}.
 */
public final class HealthObfuscation {

    private HealthObfuscation() {
    }

    /**
     * @return whether entity data of this entity must be sanitized before it's sent to other players
     */
    public static boolean appliesTo(final Entity entity) {
        return entity instanceof ServerPlayer player && player.level().canvasConfig().anticheat.obfuscatePlayerHealth;
    }

    /**
     * @return the values to send about {@code entity} to {@code receiver}: sanitized if the entity is another player
     * in a world with health obfuscation, otherwise the given values
     */
    public static List<SynchedEntityData.DataValue<?>> sanitizeFor(final Entity entity, final ServerPlayer receiver, final List<SynchedEntityData.DataValue<?>> values) {
        if (entity == receiver || !appliesTo(entity)) {
            return values;
        }
        final List<SynchedEntityData.DataValue<?>> sanitized = sanitize((LivingEntity) entity, values);
        return sanitized == null ? values : sanitized;
    }

    /**
     * @return a copy of the values with health and absorption replaced, or {@code null} if they contain neither
     */
    public static @Nullable List<SynchedEntityData.DataValue<?>> sanitize(final LivingEntity entity, final List<SynchedEntityData.DataValue<?>> values) {
        final int healthId = LivingEntity.DATA_HEALTH_ID.id();
        final int absorptionId = Player.DATA_PLAYER_ABSORPTION_ID.id();
        List<SynchedEntityData.DataValue<?>> ret = null;
        for (int i = 0, len = values.size(); i < len; ++i) {
            final SynchedEntityData.DataValue<?> value = values.get(i);
            final SynchedEntityData.DataValue<?> replacement;
            if (value.id() == healthId) {
                // keep 0 so the death animation still plays on other clients
                replacement = SynchedEntityData.DataValue.create(LivingEntity.DATA_HEALTH_ID, entity.getHealth() > 0.0F ? entity.getMaxHealth() : 0.0F);
            } else if (value.id() == absorptionId) {
                replacement = SynchedEntityData.DataValue.create(Player.DATA_PLAYER_ABSORPTION_ID, 0.0F);
            } else {
                if (ret != null) {
                    ret.add(value);
                }
                continue;
            }
            if (ret == null) {
                ret = new ArrayList<>(values.subList(0, i));
            }
            ret.add(replacement);
        }
        return ret;
    }
}
