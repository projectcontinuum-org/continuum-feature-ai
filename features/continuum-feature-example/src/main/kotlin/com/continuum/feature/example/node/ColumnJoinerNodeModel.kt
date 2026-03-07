package com.continuum.feature.example.node

import com.continuum.core.commons.exception.NodeRuntimeException
import com.continuum.core.commons.model.ContinuumWorkflowModel
import com.continuum.core.commons.node.ProcessNodeModel
import com.continuum.core.commons.prototol.progress.NodeProgressCallback
import com.continuum.core.commons.utils.NodeInputReader
import com.continuum.core.commons.utils.NodeOutputWriter
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE
import org.springframework.stereotype.Component

/**
 * Column Joiner Node Model
 *
 * Joins two columns from a single input table by concatenating their values with a configurable separator.
 * All original columns are passed through to the output, and a new column with the joined values is added.
 *
 * **Input Ports:**
 * - `input`: Input table containing the columns to join
 *
 * **Output Ports:**
 * - `output`: Table with all original columns plus the new joined column
 *
 * **Configuration Properties:**
 * - `leftColumn` (required): Name of the first column to join
 * - `rightColumn` (required): Name of the second column to join
 * - `outputColumnName` (optional, default "joined"): Name for the new output column
 * - `separator` (optional, default " "): Character(s) between the two values
 *
 * @since 1.0
 * @see ProcessNodeModel
 */
@Component
class ColumnJoinerNodeModel : ProcessNodeModel() {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(ColumnJoinerNodeModel::class.java)
        private val objectMapper = ObjectMapper()
    }

    final override val inputPorts = mapOf(
        "input" to ContinuumWorkflowModel.NodePort(
            name = "input table",
            contentType = APPLICATION_OCTET_STREAM_VALUE
        )
    )

    final override val outputPorts = mapOf(
        "output" to ContinuumWorkflowModel.NodePort(
            name = "output table",
            contentType = APPLICATION_OCTET_STREAM_VALUE
        )
    )

    override val categories = listOf(
        "Data Manipulation"
    )

    val propertiesSchema: Map<String, Any> = objectMapper.readValue(
        """
        {
          "type": "object",
          "properties": {
            "leftColumn": {
              "type": "string",
              "title": "Left Column",
              "description": "Name of the first column to join"
            },
            "rightColumn": {
              "type": "string",
              "title": "Right Column",
              "description": "Name of the second column to join"
            },
            "outputColumnName": {
              "type": "string",
              "title": "Output Column Name",
              "description": "Name for the new column containing joined values",
              "default": "joined"
            },
            "separator": {
              "type": "string",
              "title": "Separator",
              "description": "Character(s) to place between the two column values",
              "default": " "
            }
          },
          "required": ["leftColumn", "rightColumn"]
        }
        """.trimIndent(),
        object : TypeReference<Map<String, Any>>() {}
    )

    val propertiesUiSchema: Map<String, Any> = objectMapper.readValue(
        """
        {
          "type": "VerticalLayout",
          "elements": [
            {
              "type": "Control",
              "scope": "#/properties/leftColumn"
            },
            {
              "type": "Control",
              "scope": "#/properties/rightColumn"
            },
            {
              "type": "Control",
              "scope": "#/properties/outputColumnName"
            },
            {
              "type": "Control",
              "scope": "#/properties/separator"
            }
          ]
        }
        """.trimIndent(),
        object : TypeReference<Map<String, Any>>() {}
    )

    override val metadata = ContinuumWorkflowModel.NodeData(
        id = this.javaClass.name,
        description = "Joins two columns from the same table into a new column",
        title = "Column Joiner",
        subTitle = "Concatenate two columns into one",
        nodeModel = this.javaClass.name,
        icon = """
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" stroke="currentColor" strokeWidth={1.5} viewBox="0 0 24 24">
                <path d="M7 7V1.414a1 1 0 0 1 2 0V2h5a1 1 0 0 1 .8.4l.975 1.3a.5.5 0 0 1 0 .6L14.8 5.6a1 1 0 0 1-.8.4H9v10H7v-5H2a1 1 0 0 1-.8-.4L.225 9.3a.5.5 0 0 1 0-.6L1.2 7.4A1 1 0 0 1 2 7zm1 3V8H2l-.75 1L2 10zm0-5h6l.75-1L14 3H8z"/>
            </svg>
        """.trimIndent(),
        inputs = inputPorts,
        outputs = outputPorts,
        properties = mapOf(
            "leftColumn" to "firstName",
            "rightColumn" to "lastName",
            "outputColumnName" to "joined",
            "separator" to " "
        ),
        propertiesSchema = propertiesSchema,
        propertiesUISchema = propertiesUiSchema
    )

    override fun execute(
        properties: Map<String, Any>?,
        inputs: Map<String, NodeInputReader>,
        nodeOutputWriter: NodeOutputWriter,
        nodeProgressCallback: NodeProgressCallback
    ) {
        // === Validate and extract required properties ===
        val leftColumn = properties?.get("leftColumn") as String? ?: throw NodeRuntimeException(
            workflowId = "",
            nodeId = "",
            message = "leftColumn is not provided"
        )
        val rightColumn = properties["rightColumn"] as String? ?: throw NodeRuntimeException(
            workflowId = "",
            nodeId = "",
            message = "rightColumn is not provided"
        )
        val outputColumnName = properties["outputColumnName"]?.toString() ?: "joined"
        val separator = properties["separator"]?.toString() ?: " "

        LOGGER.info("Joining columns: '$leftColumn' and '$rightColumn' into '$outputColumnName' with separator '$separator'")

        val inputReader = inputs["input"] ?: throw NodeRuntimeException(
            workflowId = "",
            nodeId = "",
            message = "Input port 'input' is not connected"
        )

        val totalRows = inputReader.getRowCount()

        // === Create output writer and process rows ===
        nodeOutputWriter.createOutputPortWriter("output").use { writer ->
            inputReader.use { reader ->
                var row = reader.read()
                var rowNumber = 0L

                while (row != null) {
                    val leftValue = row[leftColumn]?.toString() ?: ""
                    val rightValue = row[rightColumn]?.toString() ?: ""
                    val joinedValue = "$leftValue$separator$rightValue".trim()

                    // Pass through all original columns plus the new joined column
                    val outputRow = row.toMutableMap<String, Any>()
                    outputRow[outputColumnName] = joinedValue

                    writer.write(rowNumber, outputRow)

                    rowNumber++
                    if (totalRows > 0) {
                        nodeProgressCallback.report((rowNumber * 100 / totalRows).toInt())
                    }

                    row = reader.read()
                }
            }
        }

        nodeProgressCallback.report(100)
        LOGGER.info("Column join completed: processed rows")
    }
}
