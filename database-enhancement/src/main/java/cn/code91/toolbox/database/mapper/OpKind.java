package cn.code91.toolbox.database.mapper;

/**
 * <b>SqlBuilder 四件套操作标识</b>
 * <p>用作 {@link SqlBuilder} per-class SQL 文本缓存的 key。</p>
 */
public enum OpKind {
    INSERT,
    UPDATE_BY_ID,
    SELECT_BY_ID,
    DELETE_BY_ID
}
