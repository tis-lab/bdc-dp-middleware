package org.biodatacatalyst.model.types;

import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Objects;
import org.biodatacatalyst.model.Generated;

/**
 * Where a page sits in the full result set.
 */
@Generated
public class PageInfo {
  private int limit;

  private int offset;

  /**
   * Total matches, not the length of this page.
   */
  private int total;

  private boolean hasMore;

  public PageInfo() {
  }

  public PageInfo(int limit, int offset, int total, boolean hasMore) {
    this.limit = limit;
    this.offset = offset;
    this.total = total;
    this.hasMore = hasMore;
  }

  public int getLimit() {
    return limit;
  }

  public void setLimit(int limit) {
    this.limit = limit;
  }

  public int getOffset() {
    return offset;
  }

  public void setOffset(int offset) {
    this.offset = offset;
  }

  /**
   * Total matches, not the length of this page.
   */
  public int getTotal() {
    return total;
  }

  public void setTotal(int total) {
    this.total = total;
  }

  public boolean getHasMore() {
    return hasMore;
  }

  public void setHasMore(boolean hasMore) {
    this.hasMore = hasMore;
  }

  @Override
  public String toString() {
    return "PageInfo{limit='" + limit + "', offset='" + offset + "', total='" + total + "', hasMore='" + hasMore + "'}";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PageInfo that = (PageInfo) o;
    return limit == that.limit &&
        offset == that.offset &&
        total == that.total &&
        hasMore == that.hasMore;
  }

  @Override
  public int hashCode() {
    return Objects.hash(limit, offset, total, hasMore);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  @Generated
  public static class Builder {
    private int limit;

    private int offset;

    /**
     * Total matches, not the length of this page.
     */
    private int total;

    private boolean hasMore;

    public PageInfo build() {
      PageInfo result = new PageInfo();
      result.limit = this.limit;
      result.offset = this.offset;
      result.total = this.total;
      result.hasMore = this.hasMore;
      return result;
    }

    public Builder limit(int limit) {
      this.limit = limit;
      return this;
    }

    public Builder offset(int offset) {
      this.offset = offset;
      return this;
    }

    /**
     * Total matches, not the length of this page.
     */
    public Builder total(int total) {
      this.total = total;
      return this;
    }

    public Builder hasMore(boolean hasMore) {
      this.hasMore = hasMore;
      return this;
    }
  }
}
