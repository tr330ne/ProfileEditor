package treeone.profileeditor;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import treeone.profileeditor.command.ProfileEditorCommand;

@Plugin(
        id = "profile-editor",
        version = BuildConstants.VERSION,
        description = ProfileEditorPlugin.DESCRIPTION,
        authors = {"TreeOne"},
        url = "https://github.com/tr330ne/ProfileEditor"
)
public class ProfileEditorPlugin implements ZenithProxyPlugin {
    public static final String DESCRIPTION = "Customize the Minecraft account's skin, name, and cape.";
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        pluginAPI.registerCommand(new ProfileEditorCommand());
    }
}
