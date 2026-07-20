package treeone.profileeditor.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.command.api.DiscordCommandContext;
import com.zenith.discord.Embed;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import treeone.profileeditor.MinecraftServices;
import treeone.profileeditor.ProfileEditorPlugin;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static com.zenith.Globals.CONFIG;

public class ProfileEditorCommand extends Command {
    private static final Pattern VALID_NAME = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("profileEditor")
            .category(CommandCategory.MANAGE)
            .aliases("pe")
            .description(ProfileEditorPlugin.DESCRIPTION)
            .usageLines(
                "status | .pe s",
                "name <newName>",
                "skin <classic/slim> [file/URL]",
                "cape [number/off]"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        com.mojang.brigadier.Command<CommandContext> showStatus = c -> status(c.getSource());
        return command("profileEditor").requires(Command::validateAccountOwner)
            .then(literal("status").executes(showStatus))
            .then(literal("s").executes(showStatus))
            .then(literal("name").then(argument("newName", word()).executes(c -> { return changeName(c.getSource(), getString(c, "newName")); })))
            .then(literal("skin")
                .executes(c -> { return skinNeedsVariant(c.getSource()); })
                .then(literal("classic")
                    .executes(c -> { return skinFile(c.getSource(), "classic"); })
                    .then(argument("url", greedyString()).executes(c -> { return skinUrl(c.getSource(), "classic", getString(c, "url")); })))
                .then(literal("slim")
                    .executes(c -> { return skinFile(c.getSource(), "slim"); })
                    .then(argument("url", greedyString()).executes(c -> { return skinUrl(c.getSource(), "slim", getString(c, "url")); })))
                .then(argument("invalid", greedyString()).executes(c -> { return skinNeedsVariant(c.getSource()); })))
            .then(literal("cape")
                .executes(c -> { return capeList(c.getSource()); })
                .then(literal("off").executes(c -> { return capeOff(c.getSource()); }))
                .then(argument("number", integer(1)).executes(c -> { return capeSet(c.getSource(), getInteger(c, "number")); })));
    }

    @Override
    public void defaultErrorHandler(Map<CommandNode<CommandContext>, CommandSyntaxException> exceptions, CommandContext context) {
        var embed = context.getEmbed();
        for (var e : exceptions.values()) {
            embed.addField("Error", e.getMessage());
        }
        defaultHandler(context);
        if (!embed.isTitlePresent()) {
            embed.title("Invalid Command Usage");
        }
        var usage = commandUsage();
        var prefix = context.getSource().commandPrefix();
        var name = usage.getName();
        var aliases = usage.getAliases();
        String aliasesLine = prefix + name
            + aliases.stream()
            .collect(Collectors.joining(" | " + prefix, " | " + prefix, ""));
        String shortName = aliases.isEmpty() ? name : aliases.getLast();
        String commandsList = usage.getUsageLines().stream()
            .map(line -> prefix + shortName + (line.isEmpty() ? "" : " " + line))
            .collect(Collectors.joining("\n"));
        embed
            .addField("Aliases", aliasesLine + "\n**Commands**\n" + commandsList, false)
            .errorColor();
    }

    private int changeName(CommandContext ctx, String newName) {
        if (!VALID_NAME.matcher(newName).matches()) {
            return error(ctx, "Invalid Name", "Must be 3-16 characters and contain only letters (a-z, A-Z), digits (0-9), and underscore (_).");
        }
        var token = requireToken(ctx);
        if (token == null) return ERROR;
        var cooldown = MinecraftServices.nameChangeInfo(token).filter(i -> !i.allowed()).orElse(null);
        if (cooldown != null) {
            var changedAt = cooldown.changedAt();
            var embed = ctx.getEmbed()
                .title("Name Update On Cooldown")
                .errorColor();
            var nextAllowed = nextAllowed(changedAt);
            if (changedAt != null) embed.addField("Last Changed", changedAt, true);
            if (nextAllowed != null) embed.addField("Next Allowed", nextAllowed, true);
            return ERROR;
        }
        var badStatus = MinecraftServices.nameAvailability(token, newName)
            .filter(s -> !"AVAILABLE".equalsIgnoreCase(s)).orElse(null);
        if (badStatus != null) {
            var duplicate = "DUPLICATE".equalsIgnoreCase(badStatus);
            ctx.getEmbed()
                .title(duplicate ? "Name Taken" : "Name Not Allowed")
                .addField("Status", badStatus, true)
                .description(duplicate
                    ? "That name is already in use."
                    : "That name is reserved or disallowed.")
                .errorColor();
            return ERROR;
        }
        var oldName = CONFIG.authentication.username;
        var result = MinecraftServices.changeName(token, newName);
        if (result.ok()) {
            var embed = ctx.getEmbed()
                .title("Name Updated!")
                .addField("Old Name", "`" + oldName + "`", true)
                .addField("New Name", "`" + newName + "`", true)
                .primaryColor();
            if (!MinecraftServices.refreshProfileAndSave()) {
                embed.description("Couldn't sync the new name into config/cache.");
            }
            return OK;
        }
        return apiError(ctx, "Name Update Failed", result,
            "Name is invalid or already taken.", "Not available yet (30-day cooldown) or the name is not allowed.");
    }

    private int skinNeedsVariant(CommandContext ctx) {
        return error(ctx, "Invalid Skin Model", "Choose between classic or slim.");
    }

    private int skinUrl(CommandContext ctx, String variant, String url) {
        var token = requireToken(ctx);
        if (token == null) return ERROR;
        return applySkinFromUrl(ctx, token, url.trim(), variant, false);
    }

    private int applySkinFromUrl(CommandContext ctx, String token, String url, String variant, boolean modelOnly) {
        return MinecraftServices.downloadPng(url)
            .map(png -> uploadSkinFile(ctx, token, png, variant, modelOnly))
            .orElseGet(() -> skinResultEmbed(ctx, MinecraftServices.setSkinFromUrl(token, url, variant), null, modelOnly));
    }

    private int uploadSkinFile(CommandContext ctx, String token, byte[] rawPng, String variant, boolean modelOnly) {
        var png = toRgbaPng(rawPng);
        return skinResultEmbed(ctx, MinecraftServices.setSkinFromFile(token, png, variant), png, modelOnly);
    }

    private int skinFile(CommandContext ctx, String variant) {
        if (!(ctx instanceof DiscordCommandContext discordContext)
                || discordContext.getMessageReceivedEvent().getMessage().getAttachments().isEmpty()) {
            return changeSkinModel(ctx, variant);
        }
        var attachment = discordContext.getMessageReceivedEvent().getMessage().getAttachments().getFirst();
        if (!attachment.isImage() || !"png".equalsIgnoreCase(attachment.getFileExtension())) {
            return error(ctx, "Invalid Attachment", "The image must be a PNG.");
        }
        var token = requireToken(ctx);
        if (token == null) return ERROR;
        byte[] png;
        try (var in = attachment.getProxy().download().join()) {
            png = in.readAllBytes();
        } catch (Exception e) {
            return error(ctx, "Download Failed", "Could not download the attachment: " + e.getClass().getSimpleName());
        }
        return uploadSkinFile(ctx, token, png, variant, false);
    }

    private int changeSkinModel(CommandContext ctx, String variant) {
        var auth = requireAuthProfile(ctx);
        if (auth == null) return ERROR;
        var skinUrl = auth.profile().skinUrl();
        if (skinUrl == null) {
            return error(ctx, "No Skin", "This account has no skin to change the model of.");
        }
        return applySkinFromUrl(ctx, auth.token(), skinUrl, variant, true);
    }

    private String requireToken(CommandContext ctx) {
        if (MinecraftServices.isOfflineAccount()) {
            errorEmbed(ctx, "Offline Account", "This account is offline.");
            return null;
        }
        var tokenOpt = MinecraftServices.accessToken();
        if (tokenOpt.isEmpty()) {
            errorEmbed(ctx, "No Auth Cache", "Could not read the account access token, is mc_auth_cache.json present?");
            return null;
        }
        return tokenOpt.get();
    }

    private int skinResultEmbed(CommandContext ctx, MinecraftServices.ApiResult result, byte[] skinPng, boolean modelOnly) {
        if (result.ok()) {
            var embed = ctx.getEmbed().title(modelOnly ? "Skin Model Updated!" : "Skin Updated!").primaryColor();
            if (skinPng != null) attachHead(embed, skinPng);
            return OK;
        }
        return apiError(ctx, "Skin Update Failed", result,
            "Invalid image, URL, or model.", "Not allowed for this account.");
    }

    private int status(CommandContext ctx) {
        var auth = requireAuthProfile(ctx);
        if (auth == null) return ERROR;
        var profile = auth.profile();
        var name = Optional.ofNullable(profile.name()).orElse(CONFIG.authentication.username);
        var variant = Optional.ofNullable(profile.skinVariant()).map(String::toLowerCase).orElse("unknown");
        var cape = profile.capes().stream().filter(MinecraftServices.Cape::active)
            .map(MinecraftServices.Cape::alias).findFirst().orElse("None");
        var embed = ctx.getEmbed()
            .title("***ProfileEditor***")
            .addField("Name", "`" + name + "`", true)
            .addField("Skin", variant, true)
            .addField("Cape", cape, true)
            .primaryColor();
        var skinUrl = profile.skinUrl();
        if (skinUrl != null) {
            MinecraftServices.downloadPng(skinUrl).ifPresent(png -> attachHead(embed, png));
        }
        return OK;
    }

    private int capeList(CommandContext ctx) {
        var auth = requireAuthProfile(ctx);
        if (auth == null) return ERROR;
        return showCapes(ctx, auth.profile().capes());
    }

    private int showCapes(CommandContext ctx, List<MinecraftServices.Cape> capes) {
        ctx.getEmbed()
            .title("Current Capes")
            .description(capes.isEmpty() ? "This account has no capes." : capesList(capes))
            .primaryColor();
        return OK;
    }

    private String capesList(List<MinecraftServices.Cape> capes) {
        var sb = new StringBuilder();
        for (int i = 0; i < capes.size(); i++) {
            var cape = capes.get(i);
            sb.append(i + 1).append(". ").append(cape.alias());
            if (cape.active()) sb.append(" (active)");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private int capeSet(CommandContext ctx, int number) {
        var auth = requireAuthProfile(ctx);
        if (auth == null) return ERROR;
        var capes = auth.profile().capes();
        if (number < 1 || number > capes.size()) {
            if (capes.isEmpty()) return showCapes(ctx, capes);
            return error(ctx, "Invalid Cape Number", capesList(capes));
        }
        var cape = capes.get(number - 1);
        var result = MinecraftServices.setCape(auth.token(), cape.id());
        if (result.ok()) {
            ctx.getEmbed().title("Cape Updated!").description(cape.alias()).primaryColor();
            return OK;
        }
        return capeError(ctx, result);
    }

    private int capeOff(CommandContext ctx) {
        var token = requireToken(ctx);
        if (token == null) return ERROR;
        var result = MinecraftServices.removeCape(token);
        if (result.ok()) {
            ctx.getEmbed().title("Cape Disabled!").primaryColor();
            return OK;
        }
        return capeError(ctx, result);
    }

    private record AuthProfile(String token, MinecraftServices.ProfileInfo profile) {}

    private AuthProfile requireAuthProfile(CommandContext ctx) {
        var token = requireToken(ctx);
        if (token == null) return null;
        var profileOpt = MinecraftServices.getProfile(token);
        if (profileOpt.isEmpty()) {
            errorEmbed(ctx, "Status Unavailable", "Could not read the account profile.");
            return null;
        }
        return new AuthProfile(token, profileOpt.get());
    }

    private void errorEmbed(CommandContext ctx, String title, String description) {
        ctx.getEmbed().title(title).description(description).errorColor();
    }

    private int error(CommandContext ctx, String title, String description) {
        errorEmbed(ctx, title, description);
        return ERROR;
    }

    private void attachHead(Embed embed, byte[] skinPng) {
        var head = renderHead(skinPng);
        if (head != null) {
            embed.fileAttachment(new Embed.FileAttachment("head.png", head)).image("attachment://head.png");
        }
    }

    private int capeError(CommandContext ctx, MinecraftServices.ApiResult result) {
        return apiError(ctx, "Cape Update Failed", result,
            "This account does not own that cape.", "Not allowed for this account.");
    }

    private int apiError(CommandContext ctx, String title, MinecraftServices.ApiResult result, String msg400, String msg403) {
        ctx.getEmbed()
            .title(title)
            .addField("Status", result.status(), true)
            .description(describeError(result, msg400, msg403))
            .errorColor();
        return ERROR;
    }

    private String describeError(MinecraftServices.ApiResult result, String msg400, String msg403) {
        return switch (result.status()) {
            case 400 -> msg400;
            case 401 -> "Access token rejected.";
            case 403 -> msg403;
            case 429 -> "Rate limited by Mojang.";
            default -> truncate(result.body());
        };
    }

    private static byte[] toRgbaPng(byte[] png) {
        try {
            var img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) return png;
            var rgba = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = rgba.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            return toPngBytes(rgba);
        } catch (Exception ignored) {
            return png;
        }
    }

    private static byte[] renderHead(byte[] skinPng) {
        try {
            var skin = ImageIO.read(new ByteArrayInputStream(skinPng));
            if (skin == null || skin.getWidth() < 64 || skin.getHeight() < 32) return null;
            int scale = 16;
            int size = 8 * scale;
            var head = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = head.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(skin.getSubimage(8, 8, 8, 8), 0, 0, size, size, null);
            g.drawImage(skin.getSubimage(40, 8, 8, 8), 0, 0, size, size, null);
            g.dispose();
            return toPngBytes(head);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] toPngBytes(BufferedImage img) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private String nextAllowed(String changedAt) {
        if (changedAt == null || changedAt.isBlank()) return null;
        try {
            return Instant.parse(changedAt).plus(Duration.ofDays(30)).toString();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String truncate(String body) {
        if (body == null || body.isBlank()) return "No response body.";
        return body.length() > 500 ? body.substring(0, 500) : body;
    }
}
