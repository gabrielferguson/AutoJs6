package org.autojs.autojs.core.database;

import android.database.sqlite.SQLiteException;  // 注意：来自 android.database.sqlite 包
import net.zetetic.database.DatabaseErrorHandler;
import net.zetetic.database.sqlcipher.SQLiteDatabase;

public final class DatabaseCipherErrorHandlerWrapper implements DatabaseErrorHandler {

    private DatabaseCipher.DatabaseCallback mCallback;

    public DatabaseCipherErrorHandlerWrapper(DatabaseCipher.DatabaseCallback callback) {
        mCallback = callback;
    }

    @Override
    public void onCorruption(SQLiteDatabase dbObj, SQLiteException exception) {
        // 正确的签名：两个参数，SQLiteException 来自 android.database.sqlite
        if (mCallback != null) {
            mCallback.onCorruption(dbObj);
        }
    }
}
