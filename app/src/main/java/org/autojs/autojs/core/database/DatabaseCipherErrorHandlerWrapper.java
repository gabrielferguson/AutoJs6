package org.autojs.autojs.core.database;

import net.zetetic.database.DatabaseErrorHandler;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteException;

public final class DatabaseCipherErrorHandlerWrapper implements DatabaseErrorHandler {

    private DatabaseCipher.DatabaseCallback mCallback;

    public DatabaseCipherErrorHandlerWrapper(DatabaseCipher.DatabaseCallback callback) {
        mCallback = callback;
    }

    @Override
    public void onCorruption(SQLiteDatabase sqLiteDatabase, SQLiteException exception) {
        // 现在使用正确的 SQLCipher DatabaseErrorHandler 接口
        if (mCallback != null) {
            mCallback.onCorruption(sqLiteDatabase);
        }
    }
}
