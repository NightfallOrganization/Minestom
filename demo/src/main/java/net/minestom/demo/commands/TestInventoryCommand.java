package net.minestom.demo.commands;

import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandContext;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.ContainerInventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.timer.TaskSchedule;
import org.jetbrains.annotations.NotNull;

public class TestInventoryCommand extends Command {
    public TestInventoryCommand() {
        super("testinventory");
        addSyntax(this::execute);
    }

    private void execute(@NotNull CommandSender commandSender, @NotNull CommandContext commandContext) {
        if (commandSender instanceof Player player) {
            var inventory = new ContainerInventory(InventoryType.CHEST_3_ROW, "TestChest");
            player.openInventory(inventory);
            MinecraftServer.getSchedulerManager().scheduleTask(() -> {
                var inventory2 = new ContainerInventory(InventoryType.CHEST_2_ROW, "TestChest 2");
                player.openInventory(inventory2);
            }, TaskSchedule.seconds(3), TaskSchedule.stop());
        }
    }
}
