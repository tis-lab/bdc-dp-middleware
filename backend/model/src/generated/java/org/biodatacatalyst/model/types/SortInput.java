package org.biodatacatalyst.model.types;

import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Objects;
import org.biodatacatalyst.model.Generated;

/**
 * One sort key.
 */
@Generated
public class SortInput {
  private String field;

  private SortDirection direction = SortDirection.ASC;

  public SortInput() {
  }

  public SortInput(String field, SortDirection direction) {
    this.field = field;
    this.direction = direction;
  }

  public String getField() {
    return field;
  }

  public void setField(String field) {
    this.field = field;
  }

  public SortDirection getDirection() {
    return direction;
  }

  public void setDirection(SortDirection direction) {
    this.direction = direction;
  }

  @Override
  public String toString() {
    return "SortInput{field='" + field + "', direction='" + direction + "'}";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SortInput that = (SortInput) o;
    return Objects.equals(field, that.field) &&
        Objects.equals(direction, that.direction);
  }

  @Override
  public int hashCode() {
    return Objects.hash(field, direction);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  @Generated
  public static class Builder {
    private String field;

    private SortDirection direction = SortDirection.ASC;

    public SortInput build() {
      SortInput result = new SortInput();
      result.field = this.field;
      result.direction = this.direction;
      return result;
    }

    public Builder field(String field) {
      this.field = field;
      return this;
    }

    public Builder direction(SortDirection direction) {
      this.direction = direction;
      return this;
    }
  }
}
