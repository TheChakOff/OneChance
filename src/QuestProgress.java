record QuestProgress(MicroTask task, int target, long value) {
    QuestProgress {
        if (task == null || target < task.min || target > task.max || value < 0 || value > target)
            throw new IllegalArgumentException("Invalid micro-quest progress");
    }

    QuestProgress advance(ActivityMetric metric, long amount) {
        if (metric != task.metric || amount <= 0 || completed()) return this;
        return new QuestProgress(task, target, Math.min(target, value + Math.min(amount, target)));
    }

    boolean completed() {
        return value == target;
    }

    float fraction() {
        return Math.min(1, Math.max(0, (float) value / target));
    }
}
