package com.ihrms.domain.support;

import java.util.EnumSet;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;

/** Hibernate id generator that assigns a {@link Cuids#newId()} on INSERT unless one is set. */
public class CuidGenerator implements BeforeExecutionGenerator {

  @Override
  public Object generate(
      SharedSessionContractImplementor session,
      Object owner,
      Object currentValue,
      EventType eventType) {
    return currentValue != null ? currentValue : Cuids.newId();
  }

  @Override
  public EnumSet<EventType> getEventTypes() {
    return EnumSet.of(EventType.INSERT);
  }
}
