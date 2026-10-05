package com.vulkanis.render.shadow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class EntityShadowCastersTest {
  @Test public void farEntitiesAreSkipped() {
    assertFalse(EntityShadowCasters.inRange(1000.0 * 1000.0, 128.0f, 1.0, 2.0));
  }
  @Test public void nearEntitiesAreKept() {
    assertTrue(EntityShadowCasters.inRange(10.0 * 10.0, 128.0f, 1.0, 2.0));
  }
  @Test public void bigEntitiesGetSizePadding() {
    assertTrue(EntityShadowCasters.inRange(140.0 * 140.0, 128.0f, 10.0, 10.0));
  }
}
