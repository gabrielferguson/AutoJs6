# SQLCipher Support in AutoJs6

## Overview

AutoJs6 now supports SQLCipher-Android v4.6.0, enabling encrypted SQLite database operations in JavaScript scripts. SQLCipher provides transparent 256-bit AES encryption for SQLite databases, ensuring your sensitive data is protected with a password.

## Features

- 🔐 **256-bit AES Encryption**: Industry-standard encryption for database files
- 🔑 **Password Protection**: Secure your databases with strong passwords
- 🔄 **Compatible API**: Same API as regular SQLite module for easy migration
- ⚡ **High Performance**: Minimal performance overhead for encryption
- 📦 **Standalone Module**: Works alongside regular SQLite without conflicts

## Installation

SQLCipher is built into AutoJs6 starting from version 6.6.5. No additional installation required.

## Usage

### Basic Example

```javascript
// Open or create an encrypted database
let db = sqlcipher.open('mydata.db', 'myPassword123');

// Create a table
db.execSQL('CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT)');

// Insert data
let userId = db.insert('users', { name: 'Alice' });
console.log('Inserted user ID:', userId);

// Query data
let cursor = db.rawQuery('SELECT * FROM users', null);
let users = cursor.all();
users.forEach(user => {
    console.log(`ID: ${user.id}, Name: ${user.name}`);
});

// Close the database
db.close();
```

### Opening a Database

```javascript
// Basic syntax: sqlcipher.open(name, password, [options], [callback])

// Simple open with password
let db = sqlcipher.open('mydb.db', 'myPassword');

// With options
let db = sqlcipher.open('mydb.db', 'myPassword', {
    version: 1,           // Database version (default: 1)
    readOnly: false       // Read-only mode (default: false)
});

// With callback for database lifecycle events
let db = sqlcipher.open('mydb.db', 'myPassword', {
    version: 1
}, {
    onCreate: function(database) {
        console.log('Database created');
        database.execSQL('CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)');
    },
    onOpen: function(database) {
        console.log('Database opened');
    },
    onUpgrade: function(database, oldVersion, newVersion) {
        console.log('Database upgraded from', oldVersion, 'to', newVersion);
        // Perform migration here
    },
    onCorruption: function(db) {
        console.error('Database corrupted');
    }
});
```

### CRUD Operations

```javascript
// INSERT
let id = db.insert('users', { 
    name: 'Bob', 
    age: 30,
    email: 'bob@example.com'
});

// QUERY
let cursor = db.query('users', ['name', 'age'], 'age > ?', ['25'], null, null, 'name ASC', '10');
let results = cursor.all();

// UPDATE
let updatedRows = db.update('users', { age: 31 }, 'name = ?', ['Bob']);

// DELETE
let deletedRows = db.delete('users', 'age < ?', ['18']);

// RAW SQL QUERY
let cursor = db.rawQuery('SELECT * FROM users WHERE name LIKE ?', ['%Bob%']);
let users = cursor.all();
```

### Working with Cursors

```javascript
let cursor = db.rawQuery('SELECT * FROM users', null);

// Get all rows at once
let allUsers = cursor.all();  // Returns NativeArray

// Get one row at a time
while (cursor.moveToNext()) {
    let user = cursor.pick();  // Returns current row as NativeObject
    console.log(user.name);
}

// Get single row (first result)
cursor = db.rawQuery('SELECT * FROM users WHERE id = ?', [1]);
let user = cursor.single();  // Automatically closes cursor
console.log(user.name);

// Get next row
cursor = db.rawQuery('SELECT * FROM users', null);
let firstUser = cursor.next();  // Returns next row or null
let secondUser = cursor.next();

// Don't forget to close cursor when done
cursor.close();
```

### Transactions

```javascript
// Basic transaction
let eventEmitter = db.transaction(function(tx) {
    db.execSQL('INSERT INTO users (name) VALUES (?)', ['Alice']);
    db.execSQL('INSERT INTO users (name) VALUES (?)', ['Bob']);
    // Don't forget to call tx.succeed() for commit
});

eventEmitter.on('end', function() {
    console.log('Transaction completed successfully');
});

eventEmitter.on('error', function(err) {
    console.error('Transaction failed:', err);
});

// Non-exclusive transaction (allows concurrent reads)
let eventEmitter = db.transaction(function(tx) {
    // Your operations here
}, false);  // false = non-exclusive
```

## Security Best Practices

### 1. Strong Passwords

```javascript
// ❌ Weak passwords
let db = sqlcipher.open('data.db', '123456');
let db = sqlcipher.open('data.db', 'password');

// ✅ Strong passwords
let db = sqlcipher.open('data.db', 'MyStr0ng!P@ssw0rd#2024');
let db = sqlcipher.open('data.db', crypto.generateSecureRandom(32).toString('base64'));
```

### 2. Password Storage

```javascript
// ❌ Don't hardcode passwords in your script
let password = 'MyPassword123';

// ✅ Store password securely using Android Keystore or prompt user
let password = storages.create('secure').get('db_password');
if (!password) {
    password = dialogs.rawInput('Enter database password:');
    storages.create('secure').put('db_password', password);
}
let db = sqlcipher.open('data.db', password);
```

### 3. Password Changes

SQLCipher doesn't directly support changing passwords. To change password:

```javascript
function changePassword(dbName, oldPassword, newPassword) {
    // Open with old password
    let oldDb = sqlcipher.open(dbName, oldPassword);
    
    // Create new database with new password
    let newDbName = dbName + '.new';
    let newDb = sqlcipher.open(newDbName, newPassword);
    
    // Copy all data (simplified example)
    let cursor = oldDb.rawQuery("SELECT sql FROM sqlite_master WHERE type='table'", null);
    let tables = cursor.all();
    tables.forEach(table => {
        if (table.sql) {
            newDb.execSQL(table.sql);
            // Copy data for each table
            let tableName = table.sql.match(/CREATE TABLE (\w+)/)[1];
            let data = oldDb.rawQuery(`SELECT * FROM ${tableName}`, null).all();
            // Insert data into new database
        }
    });
    
    oldDb.close();
    newDb.close();
    
    // Replace old database file with new one
    files.remove(dbName);
    files.rename(newDbName, dbName);
}
```

## Comparison with Regular SQLite

| Feature | SQLite | SQLCipher |
|---------|--------|-----------|
| Encryption | ❌ No | ✅ Yes (256-bit AES) |
| Password | Not required | Required |
| API | `sqlite.open(name, [options])` | `sqlcipher.open(name, password, [options])` |
| Performance | Slightly faster | Minimal overhead (~5-15%) |
| File Size | Smaller | Slightly larger (encryption overhead) |
| Use Case | Non-sensitive data | Sensitive/private data |

## Migration from SQLite to SQLCipher

To migrate an existing unencrypted SQLite database to SQLCipher:

```javascript
function migrateToEncrypted(dbName, password) {
    // Open old unencrypted database
    let oldDb = sqlite.open(dbName);
    
    // Create new encrypted database
    let newDbName = dbName + '.encrypted';
    let newDb = sqlcipher.open(newDbName, password);
    
    // Get all table schemas
    let cursor = oldDb.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND sql IS NOT NULL", null);
    let tables = cursor.all();
    
    // Recreate tables in encrypted database
    tables.forEach(table => {
        newDb.execSQL(table.sql);
        
        // Extract table name
        let match = table.sql.match(/CREATE TABLE ["`]?(\w+)["`]?/i);
        if (match) {
            let tableName = match[1];
            
            // Copy all data
            let dataCursor = oldDb.rawQuery(`SELECT * FROM ${tableName}`, null);
            let rows = dataCursor.all();
            
            rows.forEach(row => {
                newDb.insert(tableName, row);
            });
        }
    });
    
    oldDb.close();
    newDb.close();
    
    // Backup original and replace with encrypted version
    files.rename(dbName, dbName + '.backup');
    files.rename(newDbName, dbName);
    
    console.log('Migration completed. Original database backed up as ' + dbName + '.backup');
}
```

## Troubleshooting

### Wrong Password Error

If you get an error when opening the database, ensure you're using the correct password:

```javascript
try {
    let db = sqlcipher.open('data.db', 'password');
    // Try a query to verify password is correct
    db.rawQuery('SELECT 1', null).single();
} catch (e) {
    console.error('Failed to open database. Wrong password?', e);
}
```

### Database Corrupted

If the database is corrupted, SQLCipher may not be able to open it:

```javascript
let db = sqlcipher.open('data.db', 'password', {}, {
    onCorruption: function() {
        console.error('Database is corrupted!');
        // Handle corruption (restore from backup, etc.)
    }
});
```

### Performance Issues

SQLCipher adds minimal overhead (~5-15%). For better performance:

1. Use transactions for bulk operations
2. Create appropriate indexes
3. Use prepared statements for repeated queries
4. Close cursors and databases when done

## Examples in the Sample Directory

Check out these examples in the sample directory:

- `数据库/SQLCipher 基本使用 [v6.6.5+].js` - Simple introduction
- `数据库/SQLCipher 加密数据库 [v6.6.5+].js` - Comprehensive examples

## Technical Details

### Implementation

- **Library**: SQLCipher-Android v4.6.0
- **Encryption**: 256-bit AES in CBC mode
- **Key Derivation**: PBKDF2-HMAC-SHA512 (256,000 iterations)
- **Page Size**: 4096 bytes (optimized for modern devices)
- **Compatibility**: Works on Android API 26+ (Android 8.0+)

### Architecture

AutoJs6 implements SQLCipher in parallel with regular SQLite:

```
ScriptRuntime
├── sqlite (org.autojs.autojs.runtime.api.SQLite)
│   └── Uses: android.database.sqlite.SQLiteDatabase
└── sqlcipher (org.autojs.autojs.runtime.api.SQLCipher)
    └── Uses: net.zetetic.database.sqlcipher.SQLiteDatabase
```

Both modules share the same API interface, making it easy to switch between encrypted and unencrypted databases.

## License

SQLCipher is licensed under the BSD-style license. See the [SQLCipher license](https://www.zetetic.net/sqlcipher/license/) for details.

## References

- [SQLCipher Official Website](https://www.zetetic.net/sqlcipher/)
- [SQLCipher Android Documentation](https://github.com/sqlcipher/android-database-sqlcipher)
- [AutoJs6 Documentation](https://docs.autojs6.com/)

## Support

For issues or questions about SQLCipher in AutoJs6:

1. Check the sample scripts in the `数据库` directory
2. Visit the AutoJs6 GitHub repository
3. Refer to SQLCipher's official documentation for encryption-specific questions
