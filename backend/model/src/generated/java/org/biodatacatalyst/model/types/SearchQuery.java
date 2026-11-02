package org.biodatacatalyst.model.types;

import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.Objects;
import org.biodatacatalyst.model.Generated;

/**
 * Search criteria.
 */
@Generated
public class SearchQuery {
  /**
   * Free text; null matches everything.
   */
  private String text;

  private PageInput page;

  /**
   * Applied in list order.
   */
  private List<SortInput> sort;

  public SearchQuery() {
  }

  public SearchQuery(String text, PageInput page, List<SortInput> sort) {
    this.text = text;
    this.page = page;
    this.sort = sort;
  }

  /**
   * Free text; null matches everything.
   */
  public String getText() {
    return text;
  }

  public void setText(String text) {
    this.text = text;
  }

  public PageInput getPage() {
    return page;
  }

  public void setPage(PageInput page) {
    this.page = page;
  }

  /**
   * Applied in list order.
   */
  public List<SortInput> getSort() {
    return sort;
  }

  public void setSort(List<SortInput> sort) {
    this.sort = sort;
  }

  @Override
  public String toString() {
    return "SearchQuery{text='" + text + "', page='" + page + "', sort='" + sort + "'}";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SearchQuery that = (SearchQuery) o;
    return Objects.equals(text, that.text) &&
        Objects.equals(page, that.page) &&
        Objects.equals(sort, that.sort);
  }

  @Override
  public int hashCode() {
    return Objects.hash(text, page, sort);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  @Generated
  public static class Builder {
    /**
     * Free text; null matches everything.
     */
    private String text;

    private PageInput page;

    /**
     * Applied in list order.
     */
    private List<SortInput> sort;

    public SearchQuery build() {
      SearchQuery result = new SearchQuery();
      result.text = this.text;
      result.page = this.page;
      result.sort = this.sort;
      return result;
    }

    /**
     * Free text; null matches everything.
     */
    public Builder text(String text) {
      this.text = text;
      return this;
    }

    public Builder page(PageInput page) {
      this.page = page;
      return this;
    }

    /**
     * Applied in list order.
     */
    public Builder sort(List<SortInput> sort) {
      this.sort = sort;
      return this;
    }
  }
}
