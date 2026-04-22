package com.sst.flaggame.common.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.sql.Timestamp;
import java.time.LocalDateTime;

@Converter
public class DeletedAtConverter implements AttributeConverter<Boolean, Timestamp> {
    @Override
    public Timestamp convertToDatabaseColumn(Boolean deleted) {
        //NPE 발생 할 수 있어서 이렇게 바꿔야한대요.
        return Boolean.TRUE.equals(deleted) ? Timestamp.valueOf(LocalDateTime.now()) : null;
    }

    @Override
    public Boolean convertToEntityAttribute(Timestamp dbValue) {
        return dbValue != null;
    }
}

