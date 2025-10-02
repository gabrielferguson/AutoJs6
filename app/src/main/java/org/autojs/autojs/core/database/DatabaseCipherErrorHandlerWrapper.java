package org.autojs.autojs.core.database;

import net.zetetic.database.DatabaseErrorHandler;
import net.zetetic.database.sqlcipher.SQLiteDatabase;

public final class DatabaseCipherErrorHandlerWrapper implements DatabaseErrorHandler {

    private DatabaseCipher.DatabaseCallback mCallback;

    public DatabaseCipherErrorHandlerWrapper(DatabaseCipher.DatabaseCallback callback) {
        mCallback = callback;
    }

    @Override
    public void onCorruption(SQLiteDatabase sqLiteDatabase) {
        // 只有一个参数，不需要 SQLiteException
        if (mCallback != null) {
            mCallback.onCorruption(sqLiteDatabase);
        }
    }
}
