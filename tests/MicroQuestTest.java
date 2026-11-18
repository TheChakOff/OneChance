import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class MicroQuestTest {
    @Test void advancesOnlyMatchingPositiveActivityAndCapsCompletion() {
        QuestProgress quest = new QuestProgress(MicroTask.MINER,256,5);
        assertSame(quest,quest.advance(ActivityMetric.BLOCKS_PLACED,10));
        assertSame(quest,quest.advance(ActivityMetric.BLOCKS_MINED,-1));
        assertEquals(8,quest.advance(ActivityMetric.BLOCKS_MINED,3).value());
        QuestProgress finished = quest.advance(ActivityMetric.BLOCKS_MINED,Long.MAX_VALUE);
        assertEquals(256,finished.value()); assertTrue(finished.completed()); assertEquals(1f,finished.fraction());
        assertSame(finished,finished.advance(ActivityMetric.BLOCKS_MINED,100),"Completed quest must not trigger another completion");
    }
    @Test void cannotCreateInvalidTargetOrProgress() {
        assertThrows(IllegalArgumentException.class,()->new QuestProgress(MicroTask.MINER,0,0));
        assertThrows(IllegalArgumentException.class,()->new QuestProgress(MicroTask.MINER,256,257));
        assertThrows(IllegalArgumentException.class,()->new QuestProgress(MicroTask.MINER,256,-1));
    }
    @Test void equipmentGatingAndNoImmediateRepeatHoldForRandomAssignments() {
        Random random = new Random(12345);
        MicroTask previous = null;
        for (int i=0;i<500;i++) {
            MicroTask selected = MicroTask.choose(random,previous,false,false,false,false);
            assertNotEquals(previous,selected);
            assertNotEquals(MicroTask.FISH,selected); assertNotEquals(MicroTask.ORE,selected); assertNotEquals(MicroTask.HUNT,selected);
            assertNotEquals(MicroTask.DIAMOND,selected);
            int target = selected.target(random,600);
            assertTrue(target>=selected.min && target<=selected.max);
            assertFalse(selected.goal(target).isBlank());
            previous=selected;
        }
        java.util.Set<MicroTask> possible = new java.util.HashSet<>();
        for (int i=0;i<1000;i++) possible.add(MicroTask.choose(random,null,true,true,true,true));
        assertEquals(MicroTask.values().length,possible.size());
    }
    @Test void questProgressSurvivesSerializationButDoesNotCrossAttempts() throws Exception {
        var state = new YamlConfiguration();
        var record = new MicroQuestSave("owner",3,new QuestProgress(MicroTask.MINER,256,12),"STICK",450,600,false);
        record.write(state,"quest");
        var loaded = new YamlConfiguration(); loaded.loadFromString(state.saveToString());
        var restored = MicroQuestSave.read(loaded.getConfigurationSection("quest"),"owner",3);
        assertEquals(record,restored);
        assertNull(MicroQuestSave.read(loaded.getConfigurationSection("quest"),"owner",4));
        assertNull(MicroQuestSave.read(loaded.getConfigurationSection("quest"),"another",3));
    }
    @Test void completedQuestStaysCompletedAfterReconnectAndMalformedDataIsRejected() throws Exception {
        var state = new YamlConfiguration();
        new MicroQuestSave("owner",1,new QuestProgress(MicroTask.FISH,12,12),"BUSH",240,600,false).write(state,"quest");
        var loaded = new YamlConfiguration(); loaded.loadFromString(state.saveToString());
        var saved = MicroQuestSave.read(loaded.getConfigurationSection("quest"),"owner",1);
        assertTrue(saved.quest().completed());
        assertSame(saved.quest(),saved.quest().advance(ActivityMetric.FISH_CAUGHT,1));
        loaded.set("quest.task","REMOVED_TASK");
        assertNull(MicroQuestSave.read(loaded.getConfigurationSection("quest"),"owner",1));
    }

    @Test void cycleIsRandomBetweenFiveAndTwentyMinutesAndRespectsConfiguration() {
        Random random = new Random(52);
        java.util.Set<Integer> durations = new java.util.HashSet<>();
        for (int i=0;i<1000;i++) {
            int duration=QuestCycle.duration(random,5,20);
            assertTrue(duration>=300 && duration<=1200);
            durations.add(duration);
        }
        assertTrue(durations.size()>100);
        assertEquals(300,QuestCycle.duration(random,5,5));
        assertEquals(1200,QuestCycle.duration(random,20,20));
        assertEquals(1200,QuestCycle.duration(random,100,-100));
        assertEquals("05:00",QuestCycle.time(300));
        assertEquals("00:00",QuestCycle.time(-5));
    }

    @Test void skippedQuestAndExactRemainingTimeSurviveReloadAndOldSimpleQuestsAreReplaced() throws Exception {
        var state=new YamlConfiguration();
        var saved=new MicroQuestSave("owner",1,new QuestProgress(MicroTask.MINER,256,30),"PAPER",357,900,true);
        saved.write(state,"quest");
        var reloaded=new YamlConfiguration(); reloaded.loadFromString(state.saveToString());
        assertEquals(saved,MicroQuestSave.read(reloaded.getConfigurationSection("quest"),"owner",1));
        reloaded.set("quest.schema",1);
        assertNull(MicroQuestSave.read(reloaded.getConfigurationSection("quest"),"owner",1));
    }

    @Test void hardTargetsMatchTheWindowAndDiamondTasksRequireAnIronTierTool() {
        Random random=new Random(4);
        for (int seconds : new int[]{300,600,1200}) for (MicroTask task : MicroTask.values()) for (int i=0;i<100;i++) {
            int target=task.target(random,seconds);
            assertTrue(target>=task.min && target<=task.max);
            if (task==MicroTask.WALK) assertTrue(target<=seconds*400);
        }
        for (int i=0;i<500;i++) assertNotEquals(MicroTask.DIAMOND,MicroTask.choose(random,null,true,true,true,false));
    }
}
