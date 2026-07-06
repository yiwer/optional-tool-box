package cn.code91.toolbox.compare.testfixtures;

/**
 * 单字段字符串夹具：用于验证 {@code nullAsEmpty} 两态语义。
 */
public class NoteBean {

    private String note;

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
