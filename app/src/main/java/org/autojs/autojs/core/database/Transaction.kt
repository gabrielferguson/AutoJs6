package org.autojs.autojs.core.database

open class Transaction(val database: Any) {

    fun end() {
        when (database) {
            is Database -> database.endTransaction()
            is DatabaseCipher -> database.endTransaction()
        }
    }

    fun succeed() {
        when (database) {
            is Database -> database.setTransactionSuccessful()
            is DatabaseCipher -> database.setTransactionSuccessful()
        }
    }
}
