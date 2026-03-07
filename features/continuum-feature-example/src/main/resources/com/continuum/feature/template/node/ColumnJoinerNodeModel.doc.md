# Column Joiner Node

Combine text from two columns in the same table into a new output column. Perfect for creating full names from first and last name columns, merging address parts, or joining any two text columns from the same data source.

---

## What It Does

The Column Joiner Node takes two columns from a single input table and concatenates their values with a configurable separator. The result is added as a new column, and all original columns are preserved in the output.

**Key Points:**
- Joins two columns from the same input table
- Configurable separator between values (default: space)
- All original columns are passed through to the output
- Automatically converts all data types to text (numbers, booleans, etc.)
- Missing column values become empty strings

---

## Configuration

### Input Ports
| Port | Description |
|------|-------------|
| **input** | Input table containing the columns to join |

### Output Ports
| Port | Description |
|------|-------------|
| **output** | Table with all original columns plus the new joined column |

### Properties
| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| **leftColumn** | String | Yes | - | Name of the first column |
| **rightColumn** | String | Yes | - | Name of the second column |
| **outputColumnName** | String | No | "joined" | Name for the new output column |
| **separator** | String | No | " " (space) | Character(s) between the two values |

---

## How It Works

1. **Read a row** from the input table
2. **Extract values** from the left and right columns
3. **Convert to text** (numbers like 42 become "42", booleans like true become "true")
4. **Concatenate** with the separator: `"{left value}{separator}{right value}"`
5. **Trim whitespace** from the result
6. **Write output row** with all original columns plus the new joined column
7. **Report progress** as a percentage
8. **Repeat** for all remaining rows

---

## Example

**Input Table:**

| firstName | lastName | age |
|-----------|----------|-----|
| Alice | Smith | 30 |
| Bob | Jones | 25 |

**Configuration:**
- **leftColumn**: `firstName`
- **rightColumn**: `lastName`
- **outputColumnName**: `fullName`
- **separator**: ` ` (space)

**Output Table:**

| firstName | lastName | age | fullName |
|-----------|----------|-----|----------|
| Alice | Smith | 30 | Alice Smith |
| Bob | Jones | 25 | Bob Jones |

---

## Common Use Cases

- **Name merging**: Combine first and last names
- **Address building**: Join street names with city names
- **Label creation**: Merge product codes with descriptions
- **Data enrichment**: Create composite keys from multiple columns

---

## Tips & Warnings

- **Missing Columns**: If a column doesn't exist in a row, it's treated as an empty string. No error is thrown.
- **Type Conversion**: All data types are automatically converted to strings using `toString()`.
- **Original Data Preserved**: Unlike the Column Join Node which only outputs the joined column, this node keeps all original columns.
- **Separator**: The final result is trimmed, so leading/trailing whitespace from empty values won't create extra spaces.

---

## Technical Details

- **Algorithm**: Sequential row processing with streaming reader
- **Memory**: Processes one row at a time (suitable for large datasets)
- **Progress**: Reports percentage based on rows processed vs total rows
- **Resource Management**: Uses `.use {}` blocks for automatic stream cleanup
