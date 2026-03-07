package com.continuum.feature.example.node

import com.continuum.core.commons.exception.NodeRuntimeException
import com.continuum.core.commons.prototol.progress.NodeProgressCallback
import com.continuum.core.commons.utils.NodeInputReader
import com.continuum.core.commons.utils.NodeOutputWriter
import com.continuum.feature.example.node.ColumnJoinerNodeModel
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ColumnJoinerNodeModelTest {

    private lateinit var nodeModel: ColumnJoinerNodeModel
    private lateinit var mockInputReader: NodeInputReader
    private lateinit var mockOutputWriter: NodeOutputWriter
    private lateinit var mockPortWriter: NodeOutputWriter.OutputPortWriter
    private lateinit var mockProgressCallback: NodeProgressCallback

    @BeforeEach
    fun setUp() {
        nodeModel = ColumnJoinerNodeModel()
        mockInputReader = mock()
        mockOutputWriter = mock()
        mockPortWriter = mock()
        mockProgressCallback = mock()
        whenever(mockOutputWriter.createOutputPortWriter("output")).thenReturn(mockPortWriter)
    }

    // ===== Configuration Tests =====

    @Test
    fun `test node metadata is properly configured`() {
        val metadata = nodeModel.metadata
        assertEquals("com.continuum.feature.example.node.ColumnJoinerNodeModel", metadata.id)
        assertEquals("Joins two columns from the same table into a new column", metadata.description)
        assertEquals("Column Joiner", metadata.title)
        assertEquals("Concatenate two columns into one", metadata.subTitle)
        assertNotNull(metadata.icon)
        assertTrue(metadata.icon.toString().contains("svg"))
    }

    @Test
    fun `test input ports are correctly defined`() {
        val inputPorts = nodeModel.inputPorts
        assertEquals(1, inputPorts.size)
        assertNotNull(inputPorts["input"])
        assertEquals("input table", inputPorts["input"]!!.name)
    }

    @Test
    fun `test output ports are correctly defined`() {
        val outputPorts = nodeModel.outputPorts
        assertEquals(1, outputPorts.size)
        assertNotNull(outputPorts["output"])
        assertEquals("output table", outputPorts["output"]!!.name)
    }

    @Test
    fun `test categories are correctly defined`() {
        val categories = nodeModel.categories
        assertEquals(1, categories.size)
        assertEquals("Data Manipulation", categories[0])
    }

    @Test
    fun `test properties schema is valid`() {
        val schema = nodeModel.propertiesSchema
        assertNotNull(schema)
        assertEquals("object", schema["type"])
        assertTrue(schema.containsKey("properties"))
        assertTrue(schema.containsKey("required"))
    }

    // ===== Success Tests =====

    @Test
    fun `test execute joins columns successfully`() {
        val rows = listOf(
            mapOf("firstName" to "Alice", "lastName" to "Smith", "age" to 30),
            mapOf("firstName" to "Bob", "lastName" to "Jones", "age" to 25)
        )
        mockSequentialReads(mockInputReader, rows)
        whenever(mockInputReader.getRowCount()).thenReturn(2)

        val properties = mapOf(
            "leftColumn" to "firstName",
            "rightColumn" to "lastName",
            "outputColumnName" to "fullName",
            "separator" to " "
        )
        val inputs = mapOf("input" to mockInputReader)
        val rowCaptor = argumentCaptor<Map<String, Any>>()

        nodeModel.execute(properties, inputs, mockOutputWriter, mockProgressCallback)

        verify(mockPortWriter, times(2)).write(any(), rowCaptor.capture())
        assertEquals(2, rowCaptor.allValues.size)

        // Check joined values
        assertEquals("Alice Smith", rowCaptor.allValues[0]["fullName"])
        assertEquals("Bob Jones", rowCaptor.allValues[1]["fullName"])

        // Check original columns are preserved
        assertEquals("Alice", rowCaptor.allValues[0]["firstName"])
        assertEquals("Smith", rowCaptor.allValues[0]["lastName"])
        assertEquals(30, rowCaptor.allValues[0]["age"])
    }

    @Test
    fun `test execute with custom separator`() {
        val rows = listOf(
            mapOf("first" to "John", "last" to "Doe")
        )
        mockSequentialReads(mockInputReader, rows)
        whenever(mockInputReader.getRowCount()).thenReturn(1)

        val properties = mapOf(
            "leftColumn" to "first",
            "rightColumn" to "last",
            "outputColumnName" to "name",
            "separator" to ", "
        )
        val inputs = mapOf("input" to mockInputReader)
        val rowCaptor = argumentCaptor<Map<String, Any>>()

        nodeModel.execute(properties, inputs, mockOutputWriter, mockProgressCallback)

        verify(mockPortWriter, times(1)).write(any(), rowCaptor.capture())
        assertEquals("John, Doe", rowCaptor.allValues[0]["name"])
    }

    @Test
    fun `test execute uses default outputColumnName and separator`() {
        val rows = listOf(
            mapOf("a" to "hello", "b" to "world")
        )
        mockSequentialReads(mockInputReader, rows)
        whenever(mockInputReader.getRowCount()).thenReturn(1)

        val properties = mapOf(
            "leftColumn" to "a",
            "rightColumn" to "b"
        )
        val inputs = mapOf("input" to mockInputReader)
        val rowCaptor = argumentCaptor<Map<String, Any>>()

        nodeModel.execute(properties, inputs, mockOutputWriter, mockProgressCallback)

        verify(mockPortWriter, times(1)).write(any(), rowCaptor.capture())
        assertEquals("hello world", rowCaptor.allValues[0]["joined"])
    }

    @Test
    fun `test execute handles missing columns as empty strings`() {
        val rows = listOf(
            mapOf("other" to "value")
        )
        mockSequentialReads(mockInputReader, rows)
        whenever(mockInputReader.getRowCount()).thenReturn(1)

        val properties = mapOf(
            "leftColumn" to "missingCol",
            "rightColumn" to "alsoMissing",
            "outputColumnName" to "result"
        )
        val inputs = mapOf("input" to mockInputReader)
        val rowCaptor = argumentCaptor<Map<String, Any>>()

        nodeModel.execute(properties, inputs, mockOutputWriter, mockProgressCallback)

        verify(mockPortWriter, times(1)).write(any(), rowCaptor.capture())
        assertEquals("", rowCaptor.allValues[0]["result"])
    }

    @Test
    fun `test execute with numbers converted to strings`() {
        val rows = listOf(
            mapOf("num1" to 42, "num2" to 100)
        )
        mockSequentialReads(mockInputReader, rows)
        whenever(mockInputReader.getRowCount()).thenReturn(1)

        val properties = mapOf(
            "leftColumn" to "num1",
            "rightColumn" to "num2",
            "outputColumnName" to "result",
            "separator" to "-"
        )
        val inputs = mapOf("input" to mockInputReader)
        val rowCaptor = argumentCaptor<Map<String, Any>>()

        nodeModel.execute(properties, inputs, mockOutputWriter, mockProgressCallback)

        verify(mockPortWriter, times(1)).write(any(), rowCaptor.capture())
        assertEquals("42-100", rowCaptor.allValues[0]["result"])
    }

    @Test
    fun `test execute reports progress`() {
        val rows = listOf(
            mapOf("a" to "1"),
            mapOf("a" to "2"),
            mapOf("a" to "3"),
            mapOf("a" to "4")
        )
        mockSequentialReads(mockInputReader, rows)
        whenever(mockInputReader.getRowCount()).thenReturn(4)

        val properties = mapOf(
            "leftColumn" to "a",
            "rightColumn" to "a",
            "outputColumnName" to "result"
        )
        val inputs = mapOf("input" to mockInputReader)
        val progressCaptor = argumentCaptor<Int>()

        nodeModel.execute(properties, inputs, mockOutputWriter, mockProgressCallback)

        verify(mockProgressCallback, times(5)).report(progressCaptor.capture())
        // 25%, 50%, 75%, 100% from loop + final 100%
        assertEquals(25, progressCaptor.allValues[0])
        assertEquals(50, progressCaptor.allValues[1])
        assertEquals(75, progressCaptor.allValues[2])
        assertEquals(100, progressCaptor.allValues[3])
        assertEquals(100, progressCaptor.allValues[4])
    }

    @Test
    fun `test execute with row indices are sequential`() {
        val rows = listOf(
            mapOf("a" to "1"),
            mapOf("a" to "2"),
            mapOf("a" to "3")
        )
        mockSequentialReads(mockInputReader, rows)
        whenever(mockInputReader.getRowCount()).thenReturn(3)

        val properties = mapOf(
            "leftColumn" to "a",
            "rightColumn" to "a",
            "outputColumnName" to "result"
        )
        val inputs = mapOf("input" to mockInputReader)
        val indexCaptor = argumentCaptor<Long>()

        nodeModel.execute(properties, inputs, mockOutputWriter, mockProgressCallback)

        verify(mockPortWriter, times(3)).write(indexCaptor.capture(), any())
        assertEquals(0L, indexCaptor.allValues[0])
        assertEquals(1L, indexCaptor.allValues[1])
        assertEquals(2L, indexCaptor.allValues[2])
    }

    // ===== Error Tests =====

    @Test
    fun `test execute throws exception when leftColumn is missing`() {
        val properties = mapOf(
            "rightColumn" to "col2",
            "outputColumnName" to "result"
        )
        val inputs = mapOf("input" to mockInputReader)

        val exception = assertThrows<NodeRuntimeException> {
            nodeModel.execute(properties, inputs, mockOutputWriter, mockProgressCallback)
        }
        assertEquals("leftColumn is not provided", exception.message)
    }

    @Test
    fun `test execute throws exception when rightColumn is missing`() {
        val properties = mapOf(
            "leftColumn" to "col1",
            "outputColumnName" to "result"
        )
        val inputs = mapOf("input" to mockInputReader)

        val exception = assertThrows<NodeRuntimeException> {
            nodeModel.execute(properties, inputs, mockOutputWriter, mockProgressCallback)
        }
        assertEquals("rightColumn is not provided", exception.message)
    }

    @Test
    fun `test execute throws exception when input port is missing`() {
        val properties = mapOf(
            "leftColumn" to "col1",
            "rightColumn" to "col2"
        )
        val inputs = emptyMap<String, NodeInputReader>()

        val exception = assertThrows<NodeRuntimeException> {
            nodeModel.execute(properties, inputs, mockOutputWriter, mockProgressCallback)
        }
        assertEquals("Input port 'input' is not connected", exception.message)
    }

    // ===== Edge Cases =====

    @Test
    fun `test execute with empty input`() {
        mockSequentialReads(mockInputReader, emptyList())
        whenever(mockInputReader.getRowCount()).thenReturn(0)

        val properties = mapOf(
            "leftColumn" to "a",
            "rightColumn" to "b",
            "outputColumnName" to "result"
        )
        val inputs = mapOf("input" to mockInputReader)

        nodeModel.execute(properties, inputs, mockOutputWriter, mockProgressCallback)

        verify(mockPortWriter, never()).write(any(), any())
    }

    @Test
    fun `test execute properly closes output writer`() {
        val rows = listOf(mapOf("a" to "1", "b" to "2"))
        mockSequentialReads(mockInputReader, rows)
        whenever(mockInputReader.getRowCount()).thenReturn(1)

        val properties = mapOf(
            "leftColumn" to "a",
            "rightColumn" to "b",
            "outputColumnName" to "result"
        )
        val inputs = mapOf("input" to mockInputReader)

        nodeModel.execute(properties, inputs, mockOutputWriter, mockProgressCallback)

        verify(mockPortWriter).close()
    }

    @Test
    fun `test execute properly closes input reader`() {
        val rows = listOf(mapOf("a" to "1", "b" to "2"))
        mockSequentialReads(mockInputReader, rows)
        whenever(mockInputReader.getRowCount()).thenReturn(1)

        val properties = mapOf(
            "leftColumn" to "a",
            "rightColumn" to "b",
            "outputColumnName" to "result"
        )
        val inputs = mapOf("input" to mockInputReader)

        nodeModel.execute(properties, inputs, mockOutputWriter, mockProgressCallback)

        verify(mockInputReader).close()
    }

    // ===== Helper Methods =====

    private fun mockSequentialReads(reader: NodeInputReader, rows: List<Map<String, Any>>) {
        val rowsWithNull = rows + null
        var callCount = 0

        whenever(reader.read()).thenAnswer {
            val result = rowsWithNull[callCount]
            callCount++
            result
        }
    }
}
