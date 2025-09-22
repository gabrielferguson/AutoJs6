package org.autojs.autojs.core.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.database.sqlite.SQLiteTransactionListener;
import android.os.CancellationSignal;
import android.util.Pair;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.autojs.autojs.core.eventloop.EventEmitter;
import org.autojs.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.core.util.ProcessShell;

import java.io.Closeable;
import java.io.File;
import java.util.List;
import java.util.Locale;

public class Database extends SQLiteOpenHelper implements Closeable {

    private final DatabaseCallback mCallback;
    private SQLiteDatabase mDatabase;
    private final ScriptRuntime mScriptRuntime;
    private final TypeAdapter mTypeAdapter;
    private String mOriginalPath;
    private String mTempPath;
    private boolean mIsRootProtected;

    public Database(@NonNull Context context, @NonNull ScriptRuntime scriptRuntime, @NonNull String name, int version, boolean readable, @Nullable DatabaseCallback databaseCallback, @NonNull TypeAdapter typeAdapter) {
        super(context, getEffectiveDatabasePath(scriptRuntime, name), null, version, databaseCallback == null ? null : new DatabaseErrorHandlerWrapper(databaseCallback));
        mTypeAdapter = typeAdapter;
        mCallback = databaseCallback;
        mScriptRuntime = scriptRuntime;
        
        String resolvedPath = scriptRuntime.files.nonNullPath(name);
        mOriginalPath = resolvedPath;
        mIsRootProtected = isRootProtectedPath(resolvedPath);
        
        // If this is a root-protected path, copy the database to accessible location
        if (mIsRootProtected) {
            mTempPath = getDatabasePath(); // Get the temp path created by getEffectiveDatabasePath
            copyRootProtectedDatabase();
        }
        
        mDatabase = readable ? getReadableDatabase() : getWritableDatabase();
        scriptRuntime.closeableManager.add(this);
    }

    private static String getEffectiveDatabasePath(@NonNull ScriptRuntime scriptRuntime, @NonNull String name) {
        String resolvedPath = scriptRuntime.files.nonNullPath(name);
        if (isRootProtectedPath(resolvedPath)) {
            // Return a temporary path for root-protected databases
            File tempFile = new File("/data/local/tmp/", "sqlite_" + System.currentTimeMillis() + ".db");
            return tempFile.getAbsolutePath();
        }
        return resolvedPath;
    }

    private static boolean isRootProtectedPath(String path) {
        return path != null && (path.startsWith("/data/data/") || path.startsWith("/system/") || path.startsWith("/data/system/"));
    }

    private void copyRootProtectedDatabase() {
        try {
            // First check if the original database file exists
            ProcessShell.Result checkResult = ProcessShell.execCommand(
                "test -f '" + mOriginalPath + "'", 
                true
            );
            
            if (checkResult.code != 0) {
                throw new RuntimeException("Root-protected database file does not exist: " + mOriginalPath);
            }
            
            // Use root shell to copy the database file from original to temp location
            ProcessShell.Result copyResult = ProcessShell.execCommand(
                "cp '" + mOriginalPath + "' '" + mTempPath + "' && chmod 666 '" + mTempPath + "'", 
                true
            );
            
            if (copyResult.code != 0) {
                throw new RuntimeException("Failed to copy root-protected database. Error: " + copyResult.error + 
                    ", Result: " + copyResult.result);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup root-protected database access", e);
        }
    }

    private ContentValues toContentValues(Object object) {
        if (mTypeAdapter == null) {
            throw new IllegalStateException("no type adapter");
        }
        return mTypeAdapter.toContentValues(object);
    }

    private void transactionInternal(TransactionCallback transactionCallback, EventEmitter eventEmitter, boolean exclusive) {
        Transaction transaction = new Transaction(this);
        SQLiteTransactionListener listener = new SQLiteTransactionListener() {
            @Override
            public void onBegin() {
                eventEmitter.emitSticky("begin", transaction);
            }

            @Override
            public void onCommit() {
                eventEmitter.emitSticky("commit", transaction);
                eventEmitter.emitSticky("end", transaction);
            }

            @Override
            public void onRollback() {
                eventEmitter.emitSticky("rollback", transaction);
                eventEmitter.emitSticky("end", transaction);
            }
        };

        if (exclusive) {
            mDatabase.beginTransactionWithListener(listener);
        } else {
            mDatabase.beginTransactionWithListenerNonExclusive(listener);
        }

        try {
            transactionCallback.handleEvent(transaction);
            transaction.succeed();
        } catch (Exception ex) {
            eventEmitter.emitSticky("error", ex);
        } finally {
            transaction.end();
        }
    }

    private Object wrapCursor(Cursor cursor) {
        if (mTypeAdapter == null) {
            throw new IllegalStateException("no type adapter");
        }
        return mTypeAdapter.wrapCursor(cursor);
    }

    public void acquireReference() {
        mDatabase.acquireReference();
    }

    public void beginTransaction() {
        mDatabase.beginTransaction();
    }

    public void beginTransactionNonExclusive() {
        mDatabase.beginTransactionNonExclusive();
    }

    public void beginTransactionWithListener(SQLiteTransactionListener listener) {
        mDatabase.beginTransactionWithListener(listener);
    }

    public void beginTransactionWithListenerNonExclusive(SQLiteTransactionListener listener) {
        mDatabase.beginTransactionWithListenerNonExclusive(listener);
    }

    @Override
    public void close() {
        mDatabase.close();
        
        // If this was a root-protected database, sync changes back and clean up
        if (mIsRootProtected && mTempPath != null) {
            try {
                // Copy the modified database back to original location
                ProcessShell.Result syncResult = ProcessShell.execCommand(
                    "cp '" + mTempPath + "' '" + mOriginalPath + "'", 
                    true
                );
                
                if (syncResult.code != 0) {
                    android.util.Log.w("Database", "Failed to sync changes back to root-protected database: " + syncResult.error);
                }
                
                // Clean up temporary file
                ProcessShell.Result cleanupResult = ProcessShell.execCommand("rm '" + mTempPath + "'", true);
                if (cleanupResult.code != 0) {
                    android.util.Log.w("Database", "Failed to clean up temporary database file: " + mTempPath);
                }
            } catch (Exception e) {
                // Log error but don't throw - we don't want to break the close operation
                android.util.Log.w("Database", "Failed to sync root-protected database changes", e);
            }
        }
        
        mScriptRuntime.closeableManager.remove(this);
    }

    public SQLiteStatement compileStatement(String sql) {
        return mDatabase.compileStatement(sql);
    }

    public int delete(String table, String whereClause, String[] whereArgs) {
        return mDatabase.delete(table, whereClause, whereArgs);
    }

    public void disableWriteAheadLogging() {
        mDatabase.disableWriteAheadLogging();
    }

    public boolean enableWriteAheadLogging() {
        return mDatabase.enableWriteAheadLogging();
    }

    public void endTransaction() {
        mDatabase.endTransaction();
    }

    public void execSQL(String sql) {
        mDatabase.execSQL(sql);
    }

    public void execSQL(String sql, Object[] bindArgs) {
        mDatabase.execSQL(sql, bindArgs);
    }

    public List<Pair<String, String>> getAttachedDbs() {
        return mDatabase.getAttachedDbs();
    }

    public long getMaximumSize() {
        return mDatabase.getMaximumSize();
    }

    public long getPageSize() {
        return mDatabase.getPageSize();
    }

    public String getPath() {
        return mDatabase.getPath();
    }

    public String getOriginalPath() {
        return mIsRootProtected ? mOriginalPath : mDatabase.getPath();
    }

    public boolean isRootProtected() {
        return mIsRootProtected;
    }

    public TypeAdapter getTypeAdapter() {
        return mTypeAdapter;
    }

    public int getVersion() {
        return mDatabase.getVersion();
    }

    public boolean inTransaction() {
        return mDatabase.inTransaction();
    }

    public long insert(String table, Object values) {
        return mDatabase.insert(table, null, toContentValues(values));
    }

    public long insert(String table, String nullColumnHack, Object values) {
        return mDatabase.insert(table, nullColumnHack, toContentValues(values));
    }

    public long insertOrThrow(String table, String nullColumnHack, Object values) {
        return mDatabase.insertOrThrow(table, nullColumnHack, toContentValues(values));
    }

    public long insertWithOnConflict(String table, String nullColumnHack, Object initialValues, int conflictAlgorithm) {
        return mDatabase.insertWithOnConflict(table, nullColumnHack, toContentValues(initialValues), conflictAlgorithm);
    }

    public boolean isDatabaseIntegrityOk() {
        return mDatabase.isDatabaseIntegrityOk();
    }

    public boolean isDbLockedByCurrentThread() {
        return mDatabase.isDbLockedByCurrentThread();
    }

    public boolean isOpen() {
        return mDatabase.isOpen();
    }

    public boolean isReadOnly() {
        return mDatabase.isReadOnly();
    }

    public boolean isWriteAheadLoggingEnabled() {
        return mDatabase.isWriteAheadLoggingEnabled();
    }

    public boolean needUpgrade(int newVersion) {
        return mDatabase.needUpgrade(newVersion);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        mDatabase = db;
        if (mCallback != null) {
            mCallback.onCreate(this);
        }
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        mDatabase = db;
        if (mCallback != null) {
            mCallback.onOpen(this);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        mDatabase = db;
        if (mCallback != null) {
            mCallback.onUpgrade(this, oldVersion, newVersion);
        }
    }

    public Object query(String table, String[] columns, String selection, String[] selectionArgs, String groupBy, String having, String orderBy) {
        return wrapCursor(mDatabase.query(table, columns, selection, selectionArgs, groupBy, having, orderBy));
    }

    public Object query(String table, String[] columns, String selection, String[] selectionArgs, String groupBy, String having, String orderBy, String limit) {
        return wrapCursor(mDatabase.query(table, columns, selection, selectionArgs, groupBy, having, orderBy, limit));
    }

    public Object query(boolean distinct, String table, String[] columns, String selection, String[] selectionArgs, String groupBy, String having, String orderBy, String limit) {
        return wrapCursor(mDatabase.query(distinct, table, columns, selection, selectionArgs, groupBy, having, orderBy, limit));
    }

    public Object query(boolean distinct, String table, String[] columns, String selection, String[] selectionArgs, String groupBy, String having, String orderBy, String limit, CancellationSignal cancellationSignal) {
        return wrapCursor(mDatabase.query(distinct, table, columns, selection, selectionArgs, groupBy, having, orderBy, limit, cancellationSignal));
    }

    public Object queryWithFactory(SQLiteDatabase.CursorFactory cursorFactory, boolean distinct, String table, String[] columns, String selection, String[] selectionArgs, String groupBy, String having, String orderBy, String limit) {
        return wrapCursor(mDatabase.queryWithFactory(cursorFactory, distinct, table, columns, selection, selectionArgs, groupBy, having, orderBy, limit));
    }

    public Object queryWithFactory(SQLiteDatabase.CursorFactory cursorFactory, boolean distinct, String table, String[] columns, String selection, String[] selectionArgs, String groupBy, String having, String orderBy, String limit, CancellationSignal cancellationSignal) {
        return wrapCursor(mDatabase.queryWithFactory(cursorFactory, distinct, table, columns, selection, selectionArgs, groupBy, having, orderBy, limit, cancellationSignal));
    }

    public Object rawQuery(String sql, String[] selectionArgs) {
        return wrapCursor(mDatabase.rawQuery(sql, selectionArgs));
    }

    public Object rawQuery(String sql, String[] selectionArgs, CancellationSignal cancellationSignal) {
        return wrapCursor(mDatabase.rawQuery(sql, selectionArgs, cancellationSignal));
    }

    public Object rawQueryWithFactory(SQLiteDatabase.CursorFactory cursorFactory, String sql, String[] selectionArgs, String editTable) {
        return wrapCursor(mDatabase.rawQueryWithFactory(cursorFactory, sql, selectionArgs, editTable));
    }

    public Object rawQueryWithFactory(SQLiteDatabase.CursorFactory cursorFactory, String sql, String[] selectionArgs, String editTable, CancellationSignal cancellationSignal) {
        return wrapCursor(mDatabase.rawQueryWithFactory(cursorFactory, sql, selectionArgs, editTable, cancellationSignal));
    }

    public void releaseReference() {
        mDatabase.releaseReference();
    }

    public long replace(String table, String nullColumnHack, Object initialValues) {
        return mDatabase.replace(table, nullColumnHack, toContentValues(initialValues));
    }

    public long replaceOrThrow(String table, String nullColumnHack, Object initialValues) {
        return mDatabase.replaceOrThrow(table, nullColumnHack, toContentValues(initialValues));
    }

    public void setForeignKeyConstraintsEnabled(boolean enable) {
        mDatabase.setForeignKeyConstraintsEnabled(enable);
    }

    public void setLocale(Locale locale) {
        mDatabase.setLocale(locale);
    }

    public void setMaxSqlCacheSize(int cacheSize) {
        mDatabase.setMaxSqlCacheSize(cacheSize);
    }

    public long setMaximumSize(long numBytes) {
        return mDatabase.setMaximumSize(numBytes);
    }

    public void setPageSize(long numBytes) {
        mDatabase.setPageSize(numBytes);
    }

    public void setTransactionSuccessful() {
        mDatabase.setTransactionSuccessful();
    }

    public void setVersion(int version) {
        mDatabase.setVersion(version);
    }

    public EventEmitter transaction(TransactionCallback transactionCallback) {
        return transaction(transactionCallback, true);
    }

    public EventEmitter transaction(TransactionCallback transactionCallback, boolean exclusive) {
        EventEmitter eventEmitter = new EventEmitter(mScriptRuntime.bridges);
        transactionInternal(transactionCallback, eventEmitter, exclusive);
        return eventEmitter;
    }

    public int update(String table, Object values, String whereClause, String[] whereArgs) {
        return mDatabase.update(table, toContentValues(values), whereClause, whereArgs);
    }

    public int updateWithOnConflict(String table, Object values, String whereClause, String[] whereArgs, int conflictAlgorithm) {
        return mDatabase.updateWithOnConflict(table, toContentValues(values), whereClause, whereArgs, conflictAlgorithm);
    }

    public void validateSql(@NonNull String sql, @Nullable CancellationSignal cancellationSignal) {
        mDatabase.validateSql(sql, cancellationSignal);
    }

    public boolean yieldIfContendedSafely() {
        return mDatabase.yieldIfContendedSafely();
    }

    public boolean yieldIfContendedSafely(long sleepAfterYieldDelay) {
        return mDatabase.yieldIfContendedSafely(sleepAfterYieldDelay);
    }

    public interface DatabaseCallback {

        void onCorruption(SQLiteDatabase db);

        void onCreate(Database database);

        void onOpen(Database database);

        void onUpgrade(Database database, int oldVersion, int newVersion);

    }

    public interface TypeAdapter {

        ContentValues toContentValues(Object obj);

        Object wrapCursor(Cursor cursor);

    }

}