package net.onestorm.discord.verifier;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.AnnotatedEventManager;
import net.dv8tion.jda.api.hooks.SubscribeEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.onestorm.library.storage.file.FileStorage;
import net.onestorm.library.storage.file.json.JsonStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.NoSuchElementException;
import java.util.Objects;

public class VerifierBot {

    private static final Logger LOGGER = LoggerFactory.getLogger(VerifierBot.class);

    private static final String FILE_NAME = "config.json";
    private static final File FILE = new File(FILE_NAME);

    private final FileStorage storage = new JsonStorage();

    private final JDA jda;

    private String token;
    private long guildId;
    private long roleId;

    public VerifierBot(boolean updateCommand) {
        loadConfig();

        JDABuilder builder = JDABuilder.createDefault(token);
        builder.enableIntents(GatewayIntent.GUILD_MEMBERS);
        builder.setEventManager(new AnnotatedEventManager());

        jda = builder.build();
        jda.addEventListener(this);

        try {
            jda.awaitReady();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("While waiting for JDA to be ready we got interrupted! (possible command update got ignored!)");
            return;
        }

        if (updateCommand) {
            updateCommand();
        }
    }

    private void loadConfig() {
        copyConfigFromResources(); // if non-existing

        try {
            storage.load(FILE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        token = storage.getString("bot-token")
            .orElseThrow(() -> new NoSuchElementException("key/value \"bot-token\" not found in the config"));
        guildId = storage.getLong("guild-id")
            .orElseThrow(() -> new NoSuchElementException("key/value \"guild-id\" not found in the config"));
        roleId = storage.getLong("grant-role-id")
            .orElseThrow(() -> new NoSuchElementException("key/value \"grant-role-id\" not found in the config"));
    }

    private void copyConfigFromResources() {
        InputStream in = getResource();
        if (in == null) {
            throw new IllegalArgumentException("Resource " + FILE_NAME + " could not be found");
        }

        File outFile = FILE;
        // outDir and creating those dirs removed

        try {
            if (!outFile.exists()) {
                OutputStream out = new FileOutputStream(outFile);
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.close();
                in.close();
            }
        } catch (IOException e) {
            LOGGER.error("Could not save {} to {}", outFile.getName(), outFile, e);
        }
    }

    private InputStream getResource() {
        try {
            URL url = VerifierBot.class.getClassLoader().getResource(FILE_NAME);

            if (url == null) {
                return null;
            }

            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            return connection.getInputStream();
        } catch (IOException ex) {
            return null;
        }
    }

    private void updateCommand() {
        Guild guild = jda.getGuildById(guildId);

        if (guild == null) {
            LOGGER.warn("Could not update commands: guild is null");
            return;
        }

        guild.upsertCommand(createCommandData()).queue(
            ignored -> LOGGER.info("Command updated successfully!"),
            throwable -> LOGGER.warn("Failed to update command!", throwable)
        );
    }

    private SlashCommandData createCommandData() {
        SlashCommandData data = Commands.slash("admin", "Admin command for verification.");
        data.setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR));
        data.addSubcommands(
            new SubcommandData("verify-panel", "Sends a verify panel message")
        );

        return data;
    }

    @SubscribeEvent
    public void onSlashCommand(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();

        if (guild == null || guild.getIdLong() != guildId) {
            event.reply("Error: Unsupported guild (try it again later)").setEphemeral(true).queue();
            return;
        }

        String name = event.getName();

        if (!name.equalsIgnoreCase("admin")) {
            event.reply("Error: Unknown Command (Are the commands up-to-date?)").setEphemeral(true).queue();
            return;
        }

        handleAdminCommand(event);
    }

    private void handleAdminCommand(SlashCommandInteractionEvent event) {
        String name = event.getSubcommandName();
        if (name == null) {
            event.reply("Missing subcommand").setEphemeral(true).queue();
            return;
        }
        if (name.equals("verify-panel")) {
            handleVerifyPanel(event);
            return;
        }
        event.reply("Invalid subcommand").setEphemeral(true).queue();
    }

    private void handleVerifyPanel(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        EmbedBuilder builder = new EmbedBuilder();
        builder.setDescription("To get full access to our server click the button below to verify yourself!");
        builder.setColor(0x5865F2);
        MessageEmbed embed = builder.build();

        String errorType = null;
        try {
            event.getChannel().sendMessageEmbeds(embed)
                .addComponents(ActionRow.of(Button.primary("verify_me", "Verify")))
                .queue(
                    ignored -> sendMessageEmbedsSuccess(event),
                    throwable -> sendMessageEmbedsFailure(event, throwable)
                );
        } catch (InsufficientPermissionException e) {
            errorType = "INSUFFICIENT_PERMISSION";
        } catch (Exception e) {
            errorType = "EXCEPTION";
            LOGGER.warn("sendMessageEmbeds threw an exception", e);
        }

        if (errorType != null) {
            event.getHook().sendMessage("Error: Unable to send verify panel (" + errorType + ")").setEphemeral(true).queue();
        }

    }

    private void sendMessageEmbedsSuccess(SlashCommandInteractionEvent event) {
        event.getHook().sendMessage("Verify panel created!").setEphemeral(true).queue();
    }

    private void sendMessageEmbedsFailure(SlashCommandInteractionEvent event, Throwable throwable) {
        LOGGER.warn("sending message embeds failed", throwable);

        if (!(throwable instanceof ErrorResponseException exception)) {
            event.getHook().sendMessage("Error: Unable to send verify panel (UNKNOWN)").setEphemeral(true).queue();
            return;
        }

        ErrorResponse error = exception.getErrorResponse();
        event.getHook().sendMessage("Error: Unable to send verify panel (" + error.name() + ")").setEphemeral(true).queue();
    }

    @SubscribeEvent
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();

        if (guild == null || guild.getIdLong() != guildId) {
            event.reply("Error: Unsupported guild (try it again later)").setEphemeral(true).queue();
            return;
        }

        String name = event.getButton().getCustomId();

        if (name == null) {
            event.reply("Error: Invalid button (MISSING_ID)").setEphemeral(true).queue();
            return;
        }

        if (!name.equalsIgnoreCase("verify_me")) {
            event.reply("Error: Invalid button (UNKNOWN_ID)").setEphemeral(true).queue();
            return;
        }

        handleVerifyMeButton(event);
    }

    private void handleVerifyMeButton(ButtonInteractionEvent event) {
        event.deferReply(true).queue();

        Member member = Objects.requireNonNull(event.getMember());

        if (member.getRoles().stream().anyMatch(role -> role.getIdLong() == roleId)) {
            event.getHook().sendMessage("Already verified!").setEphemeral(true).queue();
            return;
        }

        Guild guild = Objects.requireNonNull(event.getGuild());
        Role memberRole = guild.getRoleById(roleId);

        if (memberRole == null) {
            event.getHook().sendMessage("Error: Try verifying again later (UNKNOWN_ROLE)").setEphemeral(true).queue();
            return;
        }

        String errorType = null;
        try {
            guild.addRoleToMember(event.getUser(), memberRole).queue(
                ignored -> addRoleSuccess(event),
                throwable -> addRoleFailure(event, throwable)
            );
        } catch (InsufficientPermissionException e) {
            errorType = "INSUFFICIENT_PERMISSION";
        } catch (HierarchyException e) {
            errorType = "HIERARCHY";
        } catch (Exception e) {
            errorType = "EXCEPTION";
            LOGGER.warn("addRoleToMember threw an exception", e);
        }

        if (errorType != null) {
            event.getHook().sendMessage("Error: Try verifying again later (" + errorType + ")").setEphemeral(true).queue();
        }
    }

    private void addRoleSuccess(ButtonInteractionEvent event) {
        event.getHook().sendMessage("Verified!").setEphemeral(true).queue();

        Guild guild = Objects.requireNonNull(event.getGuild());
        User user = event.getUser();

        LOGGER.info("Verified {}/{} in Guild {}/{}",
            user.getName(),
            user.getId(),
            guild.getName(),
            guild.getId()
        );
    }

    private void addRoleFailure(ButtonInteractionEvent event, Throwable throwable) {
        LOGGER.warn("Adding a role to a member failed", throwable);

        if (!(throwable instanceof ErrorResponseException exception)) {
            event.getHook().sendMessage("Error: Try verifying again later (UNKNOWN)").setEphemeral(true).queue();
            return;
        }

        ErrorResponse error = exception.getErrorResponse();
        event.getHook().sendMessage("Error: Try verifying again later (" + error.name() + ")").setEphemeral(true).queue();
    }

    public static void main(String[] arguments) {
        boolean update = arguments.length > 0 && arguments[0].equalsIgnoreCase("--update-command");
        new VerifierBot(update);
    }

}
