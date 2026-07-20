package me.kikugie.techutils.feature;

import me.kikugie.techutils.config.Configs;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BundleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Function;

/**
 * Inserts a full container of the held item into the player's hand. Works only in creative mode.
 * <p>
 * 1.19.4 stores container/bundle contents in item NBT (there are no data components), so contents
 * are written under {@code BlockEntityTag/Items} for block containers and {@code Items} for bundles.
 */
public class GiveFullInv {
    private static final GiveFullInv INSTANCE = new GiveFullInv();
    private static final int SHULKER_SIZE = 27;
    private static final int CHEST_SIZE = 27;

    public static boolean onKeybind() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null)
            return false;
        if (!player.getAbilities().creativeMode) {
            INSTANCE.sendError("not_creative_enough");
            return false;
        }

        ItemStack mainHand = player.getMainHandStack();
        ItemStack offHand = player.getOffHandStack();

        Optional<ItemStack> result = INSTANCE.getItem(mainHand, offHand);
        if (result.isEmpty())
            return false;

        var interaction = MinecraftClient.getInstance().interactionManager;
        if (interaction == null)
            return false;
        interaction.clickCreativeStack(result.get(), 36 + player.getInventory().selectedSlot);
        return true;
    }

    private Optional<ItemStack> getItem(ItemStack mainHand, ItemStack offHand) {
        if (mainHand.isEmpty()) {
            sendError("no_item");
            return Optional.empty();
        }
        return isShulkerBox(mainHand) ? handleBox(mainHand, offHand) : handleItem(mainHand, offHand);
    }

    private Optional<ItemStack> handleItem(ItemStack mainHand, ItemStack offHand) {
        boolean safety = Configs.MiscConfigs.FILL_SAFETY.getBooleanValue();
        if (!safety && !recursionCheck(mainHand)) {
            sendError("nested_stack");
            return Optional.empty();
        }
        ItemStack content = mainHand.copy();
        content.setCount(mainHand.getMaxCount());
        Function<ItemStack, ItemStack> filler = handleOffHand(offHand, stack -> fillShulker(stack, null));
        return Optional.of(filler.apply(content));
    }

    private Optional<ItemStack> handleBox(ItemStack mainHand, ItemStack offHand) {
        boolean safety = Configs.MiscConfigs.FILL_SAFETY.getBooleanValue();
        if (safety && isShulkerBox(offHand)) {
            sendError("nested_box");
            return Optional.empty();
        }
        ItemStack content = mainHand.copy();
        if (!containerHasItems(mainHand))
            content.setCount(64);
        Function<ItemStack, ItemStack> filler = handleOffHand(offHand, GiveFullInv::fillChest);
        return Optional.of(filler.apply(content));
    }

    private boolean recursionCheck(ItemStack mainHand) {
        if (mainHand.getItem() instanceof BundleItem)
            return !bundleHasItems(mainHand);
        return !containerHasItems(mainHand);
    }

    private Function<ItemStack, ItemStack> handleOffHand(ItemStack offHand, Function<ItemStack, ItemStack> fallback) {
        if (offHand.isEmpty())
            return fallback;
        if (offHand.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof BlockEntityProvider provider) {
            int size = containerSize(provider, blockItem.getBlock());
            if (size > 0)
                return stack -> fillContainer(blockItem, stack, size);
        }
        if (offHand.getItem() instanceof BundleItem)
            return GiveFullInv::fillBundle;
        return fallback;
    }

    private static int containerSize(BlockEntityProvider provider, Block block) {
        BlockEntity blockEntity = provider.createBlockEntity(BlockPos.ORIGIN, block.getDefaultState());
        return blockEntity instanceof Inventory inventory ? inventory.size() : 0;
    }

    public static ItemStack fillShulker(ItemStack content, @Nullable net.minecraft.util.DyeColor color) {
        return fillContainer((BlockItem) ShulkerBoxBlock.get(color).asItem(), content, SHULKER_SIZE);
    }

    public static ItemStack fillChest(ItemStack content) {
        return fillContainer((BlockItem) Blocks.CHEST.asItem(), content, CHEST_SIZE);
    }

    private static ItemStack fillContainer(BlockItem containerItem, ItemStack content, int size) {
        DefaultedList<ItemStack> items = DefaultedList.ofSize(size, ItemStack.EMPTY);
        for (int i = 0; i < size; i++)
            items.set(i, content.copy());

        NbtCompound blockEntityTag = new NbtCompound();
        Inventories.writeNbt(blockEntityTag, items);

        ItemStack container = new ItemStack(containerItem);
        container.getOrCreateNbt().put("BlockEntityTag", blockEntityTag);
        return container;
    }

    public static ItemStack fillBundle(ItemStack content) {
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        NbtList list = new NbtList();
        int count = Configs.MiscConfigs.BUNDLE_FILL.getIntegerValue();
        for (int i = 0; i < count; i++) {
            NbtCompound entry = new NbtCompound();
            content.copy().writeNbt(entry);
            list.add(entry);
        }
        bundle.getOrCreateNbt().put("Items", list);
        return bundle;
    }

    public static boolean containerHasItems(ItemStack container) {
        NbtCompound nbt = container.getSubNbt("BlockEntityTag");
        if (nbt == null || !nbt.contains("Items", NbtCompound.LIST_TYPE))
            return false;
        return !nbt.getList("Items", NbtCompound.COMPOUND_TYPE).isEmpty();
    }

    public static boolean bundleHasItems(ItemStack bundle) {
        NbtCompound nbt = bundle.getNbt();
        if (nbt == null || !nbt.contains("Items", NbtCompound.LIST_TYPE))
            return false;
        return !nbt.getList("Items", NbtCompound.COMPOUND_TYPE).isEmpty();
    }

    private boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private void sendError(String key) {
        Text message = Text.translatable("techutils.feature.givefullinv." + key).formatted(Formatting.DARK_RED);
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null)
            player.sendMessage(message, true);
    }
}
