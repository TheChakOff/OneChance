import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RewardInboxTest {
    @TempDir
    Path folder;

    @Test
    void damagedRewardEntryDoesNotBlockOpeningOrClaimingOtherPrizes() throws Exception {
        var state = new YamlConfiguration();
        UUID player = UUID.randomUUID();
        state.set("reward-inbox." + player + ".broken", "REMOVED_REWARD");
        var inbox = new RewardInbox(state, () -> {
        });
        inbox.deposit(player, "PAPER");
        assertEquals(1, inbox.list(player).size());
        assertEquals("PAPER", inbox.claim(player, inbox.list(player).getFirst().id()).reward());
        assertTrue(inbox.list(player).isEmpty());
    }

    @Test
    void invalidRewardsCannotBeDeposited() {
        var state = new YamlConfiguration();
        var inbox = new RewardInbox(state, () -> fail("Invalid reward must never reach storage"));
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> inbox.deposit(player, "REMOVED_REWARD"));
        assertThrows(IllegalArgumentException.class, () -> inbox.deposit(player, null));
        assertTrue(inbox.list(player).isEmpty());
    }

    @Test
    void prizesSurviveDiskReloadAndCanOnlyBeClaimedByTheirOwnerOnce() throws Exception {
        var state = new YamlConfiguration();
        var file = folder.resolve("state.yml").toFile();
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        var inbox = new RewardInbox(state, () -> state.save(file));
        inbox.deposit(player, "PAPER");
        inbox.deposit(player, "STICK");
        var loaded = new YamlConfiguration();
        loaded.load(file);
        var restored = new RewardInbox(loaded, () -> loaded.save(file));
        assertEquals(2, restored.list(player).size());
        var prize = restored.list(player).getFirst();
        assertNull(restored.claim(other, prize.id()));
        assertEquals(prize, restored.claim(player, prize.id()));
        assertNull(restored.claim(player, prize.id()));
        var after = new YamlConfiguration();
        after.load(file);
        assertEquals(1, new RewardInbox(after, () -> {
        }).list(player).size());
    }

    @Test
    void failedCompletionSaveDoesNotLeaveAnExtraPrizeInMemory() {
        var state = new YamlConfiguration();
        var inbox = new RewardInbox(state, () -> {
            throw new IOException("Disk full");
        });
        UUID player = UUID.randomUUID();
        assertThrows(IOException.class, () -> inbox.deposit(player, "BUSH"));
        assertTrue(inbox.list(player).isEmpty());
    }

    @Test
    void failedWithdrawalKeepsPrizeAvailable() throws Exception {
        var state = new YamlConfiguration();
        UUID player = UUID.randomUUID();
        new RewardInbox(state, () -> {
        }).deposit(player, "DIRT");
        var inbox = new RewardInbox(state, () -> {
            throw new IOException("Read-only disk");
        });
        var prize = inbox.list(player).getFirst();
        assertThrows(IOException.class, () -> inbox.claim(player, prize.id()));
        assertEquals(prize, inbox.list(player).getFirst());
        assertNull(inbox.claim(player, "../../another-player"));
    }

    @Test
    void newAttemptClearsAllPlayersIncludingOfflineButPreservesPreferencesAndStatistics() throws Exception {
        var state = new YamlConfiguration();
        var inbox = new RewardInbox(state, () -> {
        });
        UUID online = UUID.randomUUID(), offline = UUID.randomUUID();
        inbox.deposit(online, "PAPER");
        inbox.deposit(offline, "HOE");
        state.set("micro-quests.hidden." + offline, true);
        state.set("run", 7L);
        RewardInbox.clearForNewAttempt(state);
        assertTrue(inbox.list(online).isEmpty());
        assertTrue(inbox.list(offline).isEmpty());
        assertTrue(state.getBoolean("micro-quests.hidden." + offline));
        assertEquals(7L, state.getLong("run"));
    }

    @Test
    void questCompletionAndPrizeAreCommittedInSameFile() throws Exception {
        var state = new YamlConfiguration();
        var file = folder.resolve("state.yml").toFile();
        UUID player = UUID.randomUUID();
        new MicroQuestSave("owner", 1, new QuestProgress(MicroTask.FISH, 12, 12), "BUSH", 240, 600, false)
                .write(state, "micro-quests.players." + player);
        new RewardInbox(state, () -> state.save(file)).deposit(player, "BUSH");
        var loaded = new YamlConfiguration();
        loaded.load(file);
        assertTrue(MicroQuestSave.read(loaded.getConfigurationSection("micro-quests.players." + player), "owner", 1).quest().completed());
        assertEquals("BUSH", new RewardInbox(loaded, () -> {
        }).list(player).getFirst().reward());
    }
}
