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

package me.lucko.luckperms.common.commands.impl.group;

import com.google.common.collect.ImmutableList;

import me.lucko.luckperms.common.commands.abstraction.Command;
import me.lucko.luckperms.common.commands.abstraction.MainCommand;
import me.lucko.luckperms.common.commands.impl.generic.meta.CommandMeta;
import me.lucko.luckperms.common.commands.impl.generic.other.HolderClear;
import me.lucko.luckperms.common.commands.impl.generic.other.HolderShowTracks;
import me.lucko.luckperms.common.commands.impl.generic.parent.CommandParent;
import me.lucko.luckperms.common.commands.impl.generic.permission.CommandPermission;
import me.lucko.luckperms.common.commands.sender.Sender;
import me.lucko.luckperms.common.constants.Message;
import me.lucko.luckperms.common.core.model.Group;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;

import java.util.ArrayList;
import java.util.List;

public class GroupMainCommand extends MainCommand<Group> {
    public GroupMainCommand() {
        super("Group", "Group commands", "/%s group <group>", 2, ImmutableList.<Command<Group, ?>>builder()
                .add(new GroupInfo())
                .add(new CommandPermission<>(false))
                .add(new CommandParent<>(false))
                .add(new CommandMeta<>(false))
                .add(new GroupListMembers())
                .add(new GroupSetWeight())
                .add(new HolderShowTracks<>(false))
                .add(new HolderClear<>(false))
                .add(new GroupRename())
                .add(new GroupClone())
                .build()
        );
    }

    @Override
    protected Group getTarget(String target, LuckPermsPlugin plugin, Sender sender) {
        if (!plugin.getStorage().loadGroup(target).join()) {
            Message.GROUP_NOT_FOUND.send(sender);
            return null;
        }

        Group group = plugin.getGroupManager().getIfLoaded(target);

        if (group == null) {
            Message.GROUP_NOT_FOUND.send(sender);
            return null;
        }

        group.auditTemporaryPermissions();
        return group;
    }

    @Override
    protected void cleanup(Group group, LuckPermsPlugin plugin) {

    }

    @Override
    protected List<String> getTargets(LuckPermsPlugin plugin) {
        return new ArrayList<>(plugin.getGroupManager().getAll().keySet());
    }
}
