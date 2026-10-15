package com.android.launcher3.allapps;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Model-owned drawer loading state, independent of app installation progress. */
public final class DrawerLoadProgress {
    public static final class State {
        public final int progress;
        public final int completed;
        /** -1 until the loader has enumerated the drawer's icons. */
        public final int total;

        private State(int progress, int completed, int total) {
            this.progress = progress;
            this.completed = completed;
            this.total = total;
        }
    }

    private final CopyOnWriteArrayList<Consumer<State>> mListeners = new CopyOnWriteArrayList<>();
    private long mGeneration;
    private State mState = new State(-1, 0, -1);

    public synchronized long begin() {
        ++mGeneration;
        setState(0, 0, -1);
        return mGeneration;
    }

    /** Only the current loader may publish progress; 100 is reserved for UI binding. */
    public synchronized void update(long generation, int completed, int total) {
        if (generation == mGeneration && mState.progress >= 0 && mState.progress < 100) {
            total = Math.max(0, total);
            if (mState.total >= 0 && mState.total != total) return;
            completed = Math.max(mState.completed, Math.min(total, Math.max(0, completed)));
            int progress = total == 0 ? 99 : (int) (100L * completed / total);
            setState(Math.min(99, progress), completed, total);
        }
    }

    public synchronized void finish(long generation) {
        if (generation == mGeneration && mState.progress >= 0) {
            int total = Math.max(0, mState.total);
            setState(100, total, total);
        }
    }

    public synchronized void cancel(long generation) {
        if (generation == mGeneration) setState(-1, mState.completed, mState.total);
    }

    public synchronized int getProgress() {
        return mState.progress;
    }

    public synchronized State getState() {
        return mState;
    }

    public void addListener(Consumer<State> listener) {
        mListeners.addIfAbsent(listener);
        listener.accept(getState());
    }

    public void removeListener(Consumer<State> listener) {
        mListeners.remove(listener);
    }

    private void setState(int progress, int completed, int total) {
        if (progress == mState.progress && completed == mState.completed && total == mState.total) {
            return;
        }
        mState = new State(progress, completed, total);
        // Read the latest state on main so queued updates cannot resurrect a cancelled load.
        MAIN_EXECUTOR.execute(() -> {
            State current = getState();
            for (Consumer<State> listener : mListeners) listener.accept(current);
        });
    }
}
