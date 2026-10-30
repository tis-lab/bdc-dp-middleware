package org.biodatacatalyst.model.types;

import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.Objects;
import org.biodatacatalyst.model.Generated;

/**
 * One page of results.
 */
@Generated
public class SearchResult {
  private List<Item> items;

  private PageInfo page;

  public SearchResult() {
  }

  public SearchResult(List<Item> items, PageInfo page) {
    this.items = items;
    this.page = page;
  }

  public List<Item> getItems() {
    return items;
  }

  public void setItems(List<Item> items) {
    this.items = items;
  }

  public PageInfo getPage() {
    return page;
  }

  public void setPage(PageInfo page) {
    this.page = page;
  }

  @Override
  public String toString() {
    return "SearchResult{items='" + items + "', page='" + page + "'}";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SearchResult that = (SearchResult) o;
    return Objects.equals(items, that.items) &&
        Objects.equals(page, that.page);
  }

  @Override
  public int hashCode() {
    return Objects.hash(items, page);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  @Generated
  public static class Builder {
    private List<Item> items;

    private PageInfo page;

    public SearchResult build() {
      SearchResult result = new SearchResult();
      result.items = this.items;
      result.page = this.page;
      return result;
    }

    public Builder items(List<Item> items) {
      this.items = items;
      return this;
    }

    public Builder page(PageInfo page) {
      this.page = page;
      return this;
    }
  }
}
