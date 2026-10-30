package org.biodatacatalyst.model.types;

import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Objects;
import org.biodatacatalyst.model.Generated;

/**
 * Which slice of the results to return.
 */
@Generated
public class PageInput {
  /**
   * Page size.
   */
  private int limit = 20;

  /**
   * Zero-based offset.
   */
  private int offset = 0;

  public PageInput() {
  }

  public PageInput(int limit, int offset) {
    this.limit = limit;
    this.offset = offset;
  }

  /**
   * Page size.
   */
  public int getLimit() {
    return limit;
  }

  public void setLimit(int limit) {
    this.limit = limit;
  }

  /**
   * Zero-based offset.
   */
  public int getOffset() {
    return offset;
  }

  public void setOffset(int offset) {
    this.offset = offset;
  }

  @Override
  public String toString() {
    return "PageInput{limit='" + limit + "', offset='" + offset + "'}";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PageInput that = (PageInput) o;
    return limit == that.limit &&
        offset == that.offset;
  }

  @Override
  public int hashCode() {
    return Objects.hash(limit, offset);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  @Generated
  public static class Builder {
    /**
     * Page size.
     */
    private int limit = 20;

    /**
     * Zero-based offset.
     */
    private int offset = 0;

    public PageInput build() {
      PageInput result = new PageInput();
      result.limit = this.limit;
      result.offset = this.offset;
      return result;
    }

    /**
     * Page size.
     */
    public Builder limit(int limit) {
      this.limit = limit;
      return this;
    }

    /**
     * Zero-based offset.
     */
    public Builder offset(int offset) {
      this.offset = offset;
      return this;
    }
  }
}
