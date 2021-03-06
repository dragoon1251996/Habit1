/*
 * Copyright (C) 2016 Álinson Santos Xavier <isoron@gmail.com>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.models.sqlite;

import android.database.sqlite.*;
import android.support.annotation.*;
import android.support.annotation.Nullable;

import com.activeandroid.*;

import org.isoron.uhabits.models.*;
import org.isoron.uhabits.models.sqlite.records.*;
import org.isoron.uhabits.utils.*;
import org.jetbrains.annotations.*;

import java.util.*;

/**
 * Implementation of a ScoreList that is backed by SQLite.
 */
public class SQLiteScoreList extends ScoreList
{
    public static final String ADD_QUERY =
        "insert into Score(habit, timestamp, score) values (?,?,?)";

    public static final String INVALIDATE_QUERY =
        "delete from Score where habit = ? and timestamp >= ?";

    @Nullable
    private HabitRecord habitRecord;

    @NonNull
    private final SQLiteUtils<ScoreRecord> sqlite;

    @NonNull
    private final SQLiteStatement invalidateStatement;

    @NonNull
    private final SQLiteStatement addStatement;

    private final SQLiteDatabase db;

    @Nullable
    private CachedData cache = null;

    /**
     * Constructs a new ScoreList associated with the given habit.
     *
     * @param habit the habit this list should be associated with
     */
    public SQLiteScoreList(@NonNull Habit habit)
    {
        super(habit);
        sqlite = new SQLiteUtils<>(ScoreRecord.class);

        db = Cache.openDatabase();
        addStatement = db.compileStatement(ADD_QUERY);
        invalidateStatement = db.compileStatement(INVALIDATE_QUERY);
    }

    @Override
    public void add(List<Score> scores)
    {
        check(habit.getId());
        db.beginTransaction();
        try
        {
            for (Score s : scores)
            {
                addStatement.bindLong(1, habit.getId());
                addStatement.bindLong(2, s.getTimestamp());
                addStatement.bindLong(3, s.getValue());
                addStatement.execute();
            }

            db.setTransactionSuccessful();
        }
        finally
        {
            db.endTransaction();
        }
    }

    @NonNull
    @Override
    public List<Score> getByInterval(long fromTimestamp, long toTimestamp)
    {
        check(habit.getId());
        compute(fromTimestamp, toTimestamp);

        String query = "select habit, timestamp, score from Score " +
                       "where habit = ? and timestamp >= ? and timestamp <= ? " +
                       "order by timestamp desc";

        String params[] = {
            Long.toString(habit.getId()),
            Long.toString(fromTimestamp),
            Long.toString(toTimestamp)
        };

        List<ScoreRecord> records = sqlite.query(query, params);
        for (ScoreRecord record : records) record.habit = habitRecord;
        return toScores(records);
    }

    @Override
    @Nullable
    public Score getComputedByTimestamp(long timestamp)
    {
        check(habit.getId());

        String query = "select habit, timestamp, score from Score " +
                       "where habit = ? and timestamp = ? " +
                       "order by timestamp desc";

        String params[] =
            { Long.toString(habit.getId()), Long.toString(timestamp) };

        return getScoreFromQuery(query, params);
    }

    @Override
    public int getTodayValue()
    {
        if (cache == null || cache.expired())
            cache = new CachedData(super.getTodayValue());

        return cache.todayValue;
    }

    @Override
    public void invalidateNewerThan(long timestamp)
    {
        cache = null;
        invalidateStatement.bindLong(1, habit.getId());
        invalidateStatement.bindLong(2, timestamp);
        invalidateStatement.execute();
        getObservable().notifyListeners();
    }

    @Override
    @NonNull
    public List<Score> toList()
    {
        check(habit.getId());
        computeAll();

        String query = "select habit, timestamp, score from Score " +
                       "where habit = ? order by timestamp desc";

        String params[] = { Long.toString(habit.getId()) };

        List<ScoreRecord> records = sqlite.query(query, params);
        for (ScoreRecord record : records) record.habit = habitRecord;

        return toScores(records);
    }

    @Nullable
    @Override
    protected Score getNewestComputed()
    {
        check(habit.getId());
        String query = "select habit, timestamp, score from Score " +
                       "where habit = ? order by timestamp desc limit 1";

        String params[] = { Long.toString(habit.getId()) };
        return getScoreFromQuery(query, params);
    }

    @Nullable
    @Override
    protected Score getOldestComputed()
    {
        check(habit.getId());
        String query = "select habit, timestamp, score from Score " +
                       "where habit = ? order by timestamp asc limit 1";

        String params[] = { Long.toString(habit.getId()) };
        return getScoreFromQuery(query, params);
    }

    @Contract("null -> fail")
    private void check(Long id)
    {
        if (id == null) throw new RuntimeException("habit is not saved");
        if (habitRecord != null) return;
        habitRecord = HabitRecord.get(id);
        if (habitRecord == null) throw new RuntimeException("habit not found");
    }

    @Nullable
    private Score getScoreFromQuery(String query, String[] params)
    {
        ScoreRecord record = sqlite.querySingle(query, params);
        if (record == null) return null;
        record.habit = habitRecord;
        return record.toScore();
    }

    @NonNull
    private List<Score> toScores(@NonNull List<ScoreRecord> records)
    {
        List<Score> scores = new LinkedList<>();
        for (ScoreRecord r : records) scores.add(r.toScore());
        return scores;
    }

    private static class CachedData
    {
        int todayValue;

        private long today;

        CachedData(int todayValue)
        {
            this.todayValue = todayValue;
            this.today = DateUtils.getStartOfToday();
        }

        boolean expired()
        {
            return today != DateUtils.getStartOfToday();
        }
    }
}
