package org.autojs.autojs.core.database;

import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.DatabaseErrorHandler;

public final class DatabaseCipherErrorHandlerWrapper implements DatabaseErrorHandler {

    private DatabaseCipher.DatabaseCallback mCallback;

    public DatabaseCipherErrorHandlerWrapper(DatabaseCipher.DatabaseCallback callback) {
        mCallback = callback;
    }

    @Override
    public void onCorruption(SQLiteDatabase sqLiteDatabase) {
        // 现在使用 SQLCipher 的 SQLiteDatabase 类型
        if (mCallback != null) {
            mCallback.onCorruption(sqLiteDatabase);
        }
    }
}
