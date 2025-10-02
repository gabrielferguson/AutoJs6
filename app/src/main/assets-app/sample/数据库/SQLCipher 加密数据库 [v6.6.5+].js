"auto";

/**
 * SQLCipher 加密数据库示例
 * 演示如何使用 SQLCipher 创建和操作加密的 SQLite 数据库
 * 
 * SQLCipher 是 SQLite 的加密扩展，提供透明的 256 位 AES 加密
 * 使用密码保护数据库，防止未经授权的访问
 * 
 * @example
 * 
 * // 基本用法
 * let db = sqlcipher.open('secure.db', 'mySecretPassword');
 * db.execSQL('CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT)');
 * db.insert('users', { name: 'Alice' });
 * let cursor = db.rawQuery('SELECT * FROM users', null);
 * let results = cursor.all();
 * console.log(results);
 * db.close();
 */

// 数据库文件名和密码
const DB_NAME = 'encrypted_test.db';
const DB_PASSWORD = 'MyStrongPassword123!';

console.show();
console.setTitle('SQLCipher 加密数据库示例', '#4CAF50');

// 示例 1: 创建加密数据库并插入数据
function example1_createAndInsert() {
    console.log('\n===== 示例 1: 创建加密数据库并插入数据 =====');
    
    // 打开或创建加密数据库
    let db = sqlcipher.open(DB_NAME, DB_PASSWORD, {
        version: 1,
        readOnly: false
    });
    
    // 创建表
    db.execSQL('CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, age INTEGER, email TEXT)');
    
    // 插入数据
    let userId1 = db.insert('users', { name: 'Alice', age: 25, email: 'alice@example.com' });
    let userId2 = db.insert('users', { name: 'Bob', age: 30, email: 'bob@example.com' });
    let userId3 = db.insert('users', { name: 'Charlie', age: 35, email: 'charlie@example.com' });
    
    console.log('插入用户 ID:', userId1, userId2, userId3);
    
    db.close();
    console.log('数据库已关闭');
}

// 示例 2: 查询加密数据库
function example2_query() {
    console.log('\n===== 示例 2: 查询加密数据库 =====');
    
    // 使用相同密码打开数据库
    let db = sqlcipher.open(DB_NAME, DB_PASSWORD);
    
    // 查询所有用户
    console.log('\n查询所有用户:');
    let cursor = db.rawQuery('SELECT * FROM users', null);
    let users = cursor.all();
    users.forEach(user => {
        console.log(`ID: ${user.id}, 姓名: ${user.name}, 年龄: ${user.age}, 邮箱: ${user.email}`);
    });
    
    // 条件查询
    console.log('\n查询年龄大于 25 的用户:');
    cursor = db.rawQuery('SELECT * FROM users WHERE age > ?', ['25']);
    let olderUsers = cursor.all();
    olderUsers.forEach(user => {
        console.log(`姓名: ${user.name}, 年龄: ${user.age}`);
    });
    
    db.close();
}

// 示例 3: 更新和删除数据
function example3_updateAndDelete() {
    console.log('\n===== 示例 3: 更新和删除数据 =====');
    
    let db = sqlcipher.open(DB_NAME, DB_PASSWORD);
    
    // 更新数据
    let updatedRows = db.update('users', { age: 26 }, 'name = ?', ['Alice']);
    console.log('更新了', updatedRows, '行数据');
    
    // 删除数据
    let deletedRows = db.delete('users', 'name = ?', ['Charlie']);
    console.log('删除了', deletedRows, '行数据');
    
    // 验证更新和删除
    console.log('\n更新和删除后的用户列表:');
    let cursor = db.rawQuery('SELECT * FROM users', null);
    let users = cursor.all();
    users.forEach(user => {
        console.log(`ID: ${user.id}, 姓名: ${user.name}, 年龄: ${user.age}`);
    });
    
    db.close();
}

// 示例 4: 事务操作
function example4_transaction() {
    console.log('\n===== 示例 4: 事务操作 =====');
    
    let db = sqlcipher.open(DB_NAME, DB_PASSWORD);
    
    // 使用事务批量插入数据
    let eventEmitter = db.transaction(function(tx) {
        db.execSQL('CREATE TABLE IF NOT EXISTS products (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, price REAL)');
        db.insert('products', { name: 'Product A', price: 19.99 });
        db.insert('products', { name: 'Product B', price: 29.99 });
        db.insert('products', { name: 'Product C', price: 39.99 });
        // 事务成功则调用 tx.succeed()
    });
    
    eventEmitter.on('end', function() {
        console.log('事务已完成');
        
        // 查询产品
        let cursor = db.rawQuery('SELECT * FROM products', null);
        let products = cursor.all();
        console.log('产品列表:');
        products.forEach(product => {
            console.log(`${product.name}: ¥${product.price}`);
        });
        
        db.close();
    });
    
    eventEmitter.on('error', function(err) {
        console.error('事务失败:', err);
        db.close();
    });
}

// 示例 5: 错误密码演示
function example5_wrongPassword() {
    console.log('\n===== 示例 5: 错误密码演示 =====');
    
    try {
        // 尝试使用错误的密码打开数据库
        let db = sqlcipher.open(DB_NAME, 'WrongPassword');
        // 尝试执行查询会失败
        let cursor = db.rawQuery('SELECT * FROM users', null);
        cursor.all();
        db.close();
    } catch (e) {
        console.error('使用错误密码无法打开或查询数据库:', e.message);
        console.log('这证明数据库已被加密保护');
    }
}

// 主函数
function main() {
    console.log('===== SQLCipher 加密数据库演示 =====\n');
    console.log('SQLCipher 提供透明的数据库加密功能');
    console.log('使用密码保护您的敏感数据\n');
    
    // 清理旧数据库文件（如果存在）
    let dbPath = files.path(DB_NAME);
    if (files.exists(dbPath)) {
        files.remove(dbPath);
        console.log('已删除旧的数据库文件\n');
    }
    
    // 运行示例
    example1_createAndInsert();
    sleep(500);
    
    example2_query();
    sleep(500);
    
    example3_updateAndDelete();
    sleep(500);
    
    example4_transaction();
    sleep(1000);
    
    example5_wrongPassword();
    
    console.log('\n\n===== 演示完成 =====');
    console.log('提示: 始终保护好您的数据库密码！');
    console.log('SQLCipher 加密文件存储在: ' + dbPath);
}

// 运行主函数
main();
