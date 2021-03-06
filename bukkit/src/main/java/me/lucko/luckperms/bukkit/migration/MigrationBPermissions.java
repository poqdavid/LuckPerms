/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.bukkit.migration;

import de.bananaco.bpermissions.api.Calculable;
import de.bananaco.bpermissions.api.CalculableType;
import de.bananaco.bpermissions.api.Group;
import de.bananaco.bpermissions.api.Permission;
import de.bananaco.bpermissions.api.World;
import de.bananaco.bpermissions.api.WorldManager;

import me.lucko.luckperms.api.event.cause.CreationCause;
import me.lucko.luckperms.common.commands.CommandException;
import me.lucko.luckperms.common.commands.CommandResult;
import me.lucko.luckperms.common.commands.abstraction.SubCommand;
import me.lucko.luckperms.common.commands.impl.migration.MigrationUtils;
import me.lucko.luckperms.common.commands.sender.Sender;
import me.lucko.luckperms.common.core.NodeFactory;
import me.lucko.luckperms.common.core.model.PermissionHolder;
import me.lucko.luckperms.common.core.model.User;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import me.lucko.luckperms.common.utils.Predicates;
import me.lucko.luckperms.common.utils.ProgressLogger;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static me.lucko.luckperms.common.constants.Permission.MIGRATION;

public class MigrationBPermissions extends SubCommand<Object> {
    private static Field uConfigField;
    static {
        try {
            uConfigField = Class.forName("de.bananaco.bpermissions.imp.YamlWorld").getDeclaredField("uconfig");
            uConfigField.setAccessible(true);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public MigrationBPermissions() {
        super("bpermissions", "Migration from bPermissions", MIGRATION, Predicates.alwaysFalse(), null);
    }

    @Override
    public CommandResult execute(LuckPermsPlugin plugin, Sender sender, Object o, List<String> args, String label) throws CommandException {
        ProgressLogger log = new ProgressLogger("bPermissions");
        log.addListener(plugin.getConsoleSender());
        log.addListener(sender);

        log.log("Starting.");

        WorldManager worldManager = WorldManager.getInstance();
        if (worldManager == null) {
            log.logErr("Plugin not loaded.");
            return CommandResult.STATE_ERROR;
        }

        log.log("Forcing the plugin to load all data. This could take a while.");
        for (World world : worldManager.getAllWorlds()) {
            log.log("Loading users in world " + world.getName());

            YamlConfiguration yamlWorldUsers = null;
            try {
                yamlWorldUsers = (YamlConfiguration) uConfigField.get(world);
            } catch (Throwable t) {
                t.printStackTrace();
            }

            if (yamlWorldUsers == null) {
                continue;
            }

            ConfigurationSection configSection = yamlWorldUsers.getConfigurationSection("users");
            if (configSection == null) {
                continue;
            }

            Set<String> users = configSection.getKeys(false);
            if (users == null) {
                log.logErr("Couldn't get a list of users.");
                return CommandResult.FAILURE;
            }
            AtomicInteger userLoadCount = new AtomicInteger(0);
            users.forEach(s -> {
                world.loadOne(s, CalculableType.USER);
                log.logProgress("Forcefully loaded {} users so far.", userLoadCount.incrementAndGet());
            });
        }
        log.log("Forcefully loaded all users.");

        // Migrate one world at a time.
        log.log("Starting world migration.");
        for (World world : worldManager.getAllWorlds()) {
            log.log("Migrating world: " + world.getName());

            // Migrate all groups
            log.log("Starting group migration in world " + world.getName() + ".");
            AtomicInteger groupCount = new AtomicInteger(0);
            for (Calculable group : world.getAll(CalculableType.GROUP)) {
                String groupName = MigrationUtils.standardizeName(group.getName());
                if (group.getName().equalsIgnoreCase(world.getDefaultGroup())) {
                    groupName = "default";
                }

                // Make a LuckPerms group for the one being migrated.
                plugin.getStorage().createAndLoadGroup(groupName, CreationCause.INTERNAL).join();
                me.lucko.luckperms.common.core.model.Group lpGroup = plugin.getGroupManager().getIfLoaded(groupName);

                MigrationUtils.setGroupWeight(lpGroup, group.getPriority());
                migrateHolder(world, group, lpGroup);

                plugin.getStorage().saveGroup(lpGroup);

                log.logAllProgress("Migrated {} groups so far.", groupCount.incrementAndGet());
            }
            log.log("Migrated " + groupCount.get() + " groups in world " + world.getName() + ".");


            // Migrate all users
            log.log("Starting user migration in world " + world.getName() + ".");
            AtomicInteger userCount = new AtomicInteger(0);
            for (Calculable user : world.getAll(CalculableType.USER)) {
                // There is no mention of UUIDs in the API. I assume that name = uuid. idk?
                UUID uuid = null;
                try {
                    uuid = UUID.fromString(user.getName());
                } catch (IllegalArgumentException e) {
                    try {
                        //noinspection deprecation
                        uuid = Bukkit.getOfflinePlayer(user.getName()).getUniqueId();
                    } catch (Exception ex) {
                        e.printStackTrace();
                    }
                }

                if (uuid == null) {
                    log.logErr("Unable to migrate user " + user.getName() + ". Cannot to get UUID.");
                    continue;
                }

                // Make a LuckPerms user for the one being migrated.
                plugin.getStorage().loadUser(uuid, "null").join();
                User lpUser = plugin.getUserManager().get(uuid);

                migrateHolder(world, user, lpUser);

                plugin.getStorage().saveUser(lpUser);
                plugin.getUserManager().cleanup(lpUser);

                log.logProgress("Migrated {} users so far.", userCount.incrementAndGet());
            }

            log.log("Migrated " + userCount.get() + " users in world " + world.getName() + ".");
        }

        log.log("Success! Migration complete.");
        return CommandResult.SUCCESS;
    }

    private static void migrateHolder(World world, Calculable c, PermissionHolder holder) {
        // Migrate the groups permissions in this world
        for (Permission p : c.getPermissions()) {
            if (p.name().isEmpty()) {
                continue;
            }
            holder.setPermission(NodeFactory.make(p.name(), p.isTrue(), "global", world.getName()));

            // Include any child permissions
            for (Map.Entry<String, Boolean> child : p.getChildren().entrySet()) {
                if (child.getKey().isEmpty()) {
                    continue;
                }

                holder.setPermission(NodeFactory.make(child.getKey(), child.getValue(), "global", world.getName()));
            }
        }

        // Migrate any inherited groups
        for (Group parent : c.getGroups()) {
            String parentName = MigrationUtils.standardizeName(parent.getName());
            if (parent.getName().equalsIgnoreCase(world.getDefaultGroup())) {
                parentName = "default";
            }

            holder.setPermission(NodeFactory.make("group." + parentName, true, "global", world.getName()));
        }

        // Migrate existing meta
        for (Map.Entry<String, String> meta : c.getMeta().entrySet()) {
            if (meta.getKey().isEmpty() || meta.getValue().isEmpty()) {
                continue;
            }

            if (meta.getKey().equalsIgnoreCase("prefix") || meta.getKey().equalsIgnoreCase("suffix")) {
                holder.setPermission(NodeFactory.makeChatMetaNode(meta.getKey().equalsIgnoreCase("prefix"), c.getPriority(), meta.getValue()).setWorld(world.getName()).build());
                continue;
            }

            holder.setPermission(NodeFactory.makeMetaNode(meta.getKey(), meta.getValue()).setWorld(world.getName()).build());
        }
    }
}
