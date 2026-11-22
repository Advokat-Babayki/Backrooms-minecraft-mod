package com.advokatbabayki.backrooms;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * Drinkable almond water: food value plus a small heal, and it can be drunk
 * even with a full stomach because it is medicine as much as food.
 */
public class AlmondWaterItem extends Item {
    private static final int DRINK_DURATION = 32;
    private static final float HEAL_AMOUNT = 4.0F;

    public static final FoodProperties FOOD = new FoodProperties.Builder()
            .nutrition(4)
            .saturationModifier(0.6F)
            .alwaysEdible()
            .build();

    public AlmondWaterItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        // Vanilla applies nutrition and shrinks the stack; the heal is on top of that.
        ItemStack result = super.finishUsingItem(stack, level, entity);
        if (!level.isClientSide) {
            entity.heal(HEAL_AMOUNT);
        }
        return result;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return DRINK_DURATION;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.DRINK;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return ItemUtils.startUsingInstantly(level, player, hand);
    }
}
