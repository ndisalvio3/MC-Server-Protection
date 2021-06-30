/*
 * This software is licensed under the MIT License
 * https://github.com/GStefanowich/MC-Server-Protection
 *
 * Copyright (c) 2019 Gregory Stefanowich
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.TheElm.project.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.TheElm.project.CoreMod;
import net.TheElm.project.ServerCore;
import net.TheElm.project.config.SewConfig;
import net.TheElm.project.enums.OpLevels;
import net.TheElm.project.interfaces.ShopSignBlockEntity;
import net.TheElm.project.utilities.CommandUtils;
import net.TheElm.project.utilities.RankUtils;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Collection;

public final class ModCommands {
    private ModCommands() {
    }
    
    public static void register(@NotNull CommandDispatcher<ServerCommandSource> dispatcher) {
        ServerCore.register(dispatcher, CoreMod.MOD_ID, (builder) -> builder
            .requires(CommandUtils.requires(OpLevels.STOP))
            .then(CommandManager.literal("reload")
                .then(CommandManager.literal("config")
                    .executes(ModCommands::reloadConfig)
                )
                .then(CommandManager.literal("permissions")
                    .requires((source) -> SewConfig.get(SewConfig.HANDLE_PERMISSIONS))
                    .executes(ModCommands::reloadPermissions)
                )
            )
            .then(CommandManager.literal("shops")
                .then(CommandManager.literal("change-owner")
                    .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                        .then(CommandManager.argument("owner", GameProfileArgumentType.gameProfile())
                            .suggests(CommandUtils::getAllPlayerNames)
                            .executes(ModCommands::shopSignChangeOwner)
                        )
                    )
                )
            )
        );
    }
    
    private static int reloadConfig(@NotNull CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        try {
            // Reload the config
            SewConfig.reload();
            source.sendFeedback(new LiteralText("Config has been reloaded.").formatted(Formatting.GREEN), true);
            
            // Re-send the command-tree to all players
            ModCommands.reloadCommandTree(source.getMinecraftServer(), false);
            
            return Command.SINGLE_SUCCESS;
        } catch (IOException e) {
            source.sendFeedback(new LiteralText("Failed to reload config, see console for errors.").formatted(Formatting.RED), true);
            CoreMod.logError( e );
            return -1;
        }
    }
    
    private static int reloadPermissions(@NotNull CommandContext<ServerCommandSource> context) {
        boolean success = RankUtils.reload();
        ServerCommandSource source = context.getSource();
        
        if (!success)
            source.sendFeedback(new LiteralText("Failed to reload permissions, see console for errors").formatted(Formatting.RED), true);
        else{
            ModCommands.reloadCommandTree(source.getMinecraftServer(), true);
            source.sendFeedback(new LiteralText("Permissions file has been reloaded").formatted(Formatting.GREEN), true);
        }
        
        return success ? Command.SINGLE_SUCCESS : -1;
    }
    
    private static void reloadCommandTree(@NotNull MinecraftServer server, boolean reloadPermissions) {
        PlayerManager playerManager = server.getPlayerManager();
        
        // Clear permissions
        if (reloadPermissions)
            RankUtils.clearRanks();

        // Resend the player the command tree
        for (ServerPlayerEntity player : playerManager.getPlayerList())
            playerManager.sendCommandTree(player);
    }
    
    private static int shopSignChangeOwner(@NotNull CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();
        
        BlockPos signPos = BlockPosArgumentType.getBlockPos(context, "pos");
        BlockEntity blockEntity = world.getBlockEntity(signPos);
        
        // Get the target player
        Collection<GameProfile> gameProfiles = GameProfileArgumentType.getProfileArgument(context, "owner");
        GameProfile targetPlayer = gameProfiles.stream().findAny()
            .orElseThrow(GameProfileArgumentType.UNKNOWN_PLAYER_EXCEPTION::create);
        
        // If the shop sign block was not found
        if (!(blockEntity instanceof ShopSignBlockEntity)) {
            source.sendError(new LiteralText("Block at that position is not a Shop Sign."));
            return 0;
        }
        
        // Clear the editor of the sign (Causes re-rendering issues)
        if (blockEntity instanceof SignBlockEntity)
            ((SignBlockEntity) blockEntity).setEditor(null);
        
        // Get the entity as the shop sign
        ShopSignBlockEntity shop = (ShopSignBlockEntity) blockEntity;
        
        // Update the owner of the shop to the target
        shop.setShopOwner(targetPlayer.getId());
        
        // Re-Render the sign after updating the owner
        shop.renderSign(targetPlayer);
        ServerCore.markDirty(world, signPos);
        
        return Command.SINGLE_SUCCESS;
    }
}