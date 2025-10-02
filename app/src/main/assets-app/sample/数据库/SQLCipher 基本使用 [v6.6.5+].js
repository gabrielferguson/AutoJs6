"auto";

/**
 * SQLCipher 基本使用示例
 * 演示最简单的加密数据库操作
 */

console.show();
console.setTitle('SQLCipher 基本使用', '#4CAF50');

// 1. 打开或创建加密数据库（需要提供密码）
let db = sqlcipher.open('mydata.db', 'myPassword123');

// 2. 创建表
db.execSQL('CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT, created_at INTEGER)');

// 3. 插入数据
let id1 = db.insert('notes', { 
    content: '这是一条加密的笔记', 
    created_at: Date.now() 
});
console.log('插入笔记 ID:', id1);

// 4. 查询数据
let cursor = db.rawQuery('SELECT * FROM notes', null);
let notes = cursor.all();

console.log('\n笔记列表:');
notes.forEach(note => {
    console.log(`ID: ${note.id}, 内容: ${note.content}`);
});

// 5. 更新数据
db.update('notes', { content: '这是更新后的加密笔记' }, 'id = ?', [id1]);
console.log('\n已更新笔记 ID:', id1);

// 6. 再次查询验证更新
cursor = db.rawQuery('SELECT * FROM notes WHERE id = ?', [id1]);
let updatedNote = cursor.single();
console.log('更新后的笔记:', updatedNote.content);

// 7. 关闭数据库
db.close();

console.log('\n操作完成！数据已加密存储。');
console.log('注意: 只有使用相同密码才能打开和读取这个数据库。');
