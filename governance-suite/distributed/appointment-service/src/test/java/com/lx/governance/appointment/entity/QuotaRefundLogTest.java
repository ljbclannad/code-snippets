package com.lx.governance.appointment.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class QuotaRefundLogTest {

    @Test
    void nonTableColumns_areMarkedTransientForMybatisPlus() throws Exception {
        assertFalse(hasPersistentMapping("status"));
        assertFalse(hasPersistentMapping("failReason"));
        assertFalse(hasPersistentMapping("retryCount"));
    }

    private boolean hasPersistentMapping(String fieldName) throws Exception {
        Field field = QuotaRefundLog.class.getDeclaredField(fieldName);
        TableField tableField = field.getAnnotation(TableField.class);
        return tableField == null || tableField.exist();
    }
}
