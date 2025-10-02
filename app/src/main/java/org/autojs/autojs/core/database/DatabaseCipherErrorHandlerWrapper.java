package org.autojs.autojs.core.database;

import android.database.DatabaseErrorHandler;
import net.zetetic.database.sqlcipher.SQLiteDatabase;

public final class DatabaseCipherErrorHandlerWrapper implements DatabaseErrorHandler {

    private DatabaseCipher.DatabaseCallback mCallback;

    public DatabaseCipherErrorHandlerWrapper(DatabaseCipher.DatabaseCallback callback) {
        mCallback = callback;
    }

    @Override
    public void onCorruption(android.database.sqlite.SQLiteDatabase sqLiteDatabase) {
        // Note: We're using the android.database.sqlite.SQLiteDatabase type from the interface,
        // but SQLCipher uses net.zetetic.database.sqlcipher.SQLiteDatabase internally.
        // This handler needs to be adapted to work with SQLCipher's database type.
        // For now, we'll not call the callback with the database parameter.
        // A better implementation would require refactoring the callback interface.
        if (mCallback != null) {
            // Convert android.database.sqlite.SQLiteDatabase to SQLiteDatabase if possible
            // For safety, we pass null as the database is incompatible
            mCallback.onCorruption(null);
        }
    }

}
