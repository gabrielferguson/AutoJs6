package org.autojs.autojs.core.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.os.CancellationSignal;
import android.util.Pair;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteOpenHelper;
import net.zetetic.database.sqlcipher.SQLiteStatement;
import net.zetetic.database.sqlcipher.SQLiteTransactionListener;
import org.autojs.autojs.core.eventloop.EventEmitter;
import org.autojs.autojs.runtime.ScriptRuntime;

import java.io.Closeable;
import java.io.File;
import java.util.List;
import java.util.Locale;

public class DatabaseCipher extends SQLiteOpenHelper implements Closeable {

    private final DatabaseCallback mCallback;
    private SQLiteDatabase mDatabase;
    private final ScriptRuntime mScriptRuntime;
    private final TypeAdapter mTypeAdapter;
    private final String mPassword;
    private final String mDatabasePath;

    public DatabaseCipher(@NonNull Context context, @NonNull ScriptRuntime scriptRuntime, @NonNull String name, int version, @NonNull String password, boolean readable, @Nullable DatabaseCallback databaseCallback, @Nullable TypeAdapter typeAdapter) {
        super(context, scriptRuntime.files.nonNullPath(name), null, version);
        mTypeAdapter = typeAdapter;
        mCallback = databaseCallback;
        mScriptRuntime = scriptRuntime;
        mPassword = password;
        mDatabasePath = scriptRuntime.files.nonNullPath(name);
        
        // 加载 SQLCipher 库
        System.loadLibrary("sqlcipher");
        
        // 使用正确的 API 打开数据库
        File dbFile = context.getDatabasePath(mDatabasePath);
        byte[] passwordBytes = password.getBytes();
        
        // 正确的方法签名：path, password, factory, flags, databaseHook
        int flags = readable ? SQLiteDatabase.OPEN_READONLY : 
                    (SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.CREATE_IF_NECESSARY);
        
        mDatabase = SQLiteDatabase.openDatabase(
            dbFile.getAbsolutePath(), 
            passwordBytes, 
            null, 
            flags, 
            null  // SQLiteDatabaseHook，不是 DatabaseErrorHandler
        );
        
        // 手动处理数据库版本
        int currentVersion = mDatabase.getVersion();
        if (currentVersion == 0) {
            mDatabase.setVersion(version);
            onCreate(mDatabase);
        } else if (currentVersion < version) {
            onUpgrade(mDatabase, currentVersion, version);
            mDatabase.setVersion(version);
        }
        onOpen(mDatabase);

        scriptRuntime.closeableManager.add(this);
    }

    @Override
    public String getDatabaseName() {
        return mDatabasePath;
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
        if (mDatabase != null && mDatabase.isOpen()) {
            mDatabase.close();
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
        void onCreate(DatabaseCipher database);
        void onOpen(DatabaseCipher database);
        void onUpgrade(DatabaseCipher database, int oldVersion, int newVersion);
    }

    public interface TypeAdapter {
        ContentValues toContentValues(Object obj);
        Object wrapCursor(Cursor cursor);
    }
}
