/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.gchq.gaffer.hbasestore.serialisation;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import uk.gov.gchq.gaffer.commonutil.ByteArrayEscapeUtils;
import uk.gov.gchq.gaffer.commonutil.CommonConstants;
import uk.gov.gchq.gaffer.data.element.Edge;
import uk.gov.gchq.gaffer.data.element.Element;
import uk.gov.gchq.gaffer.data.element.Entity;
import uk.gov.gchq.gaffer.data.element.Properties;
import uk.gov.gchq.gaffer.exception.SerialisationException;
import uk.gov.gchq.gaffer.hbasestore.utils.ByteEntityPositions;
import uk.gov.gchq.gaffer.hbasestore.utils.HBaseStoreConstants;
import uk.gov.gchq.gaffer.hbasestore.utils.Pair;
import uk.gov.gchq.gaffer.serialisation.Serialisation;
import uk.gov.gchq.gaffer.serialisation.implementation.raw.CompactRawSerialisationUtils;
import uk.gov.gchq.gaffer.store.schema.Schema;
import uk.gov.gchq.gaffer.store.schema.SchemaElementDefinition;
import uk.gov.gchq.gaffer.store.schema.TypeDefinition;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

/**
 * The ByteEntityHBaseElementConverter converts Gaffer Elements to HBase
 * Keys And Values
 * <p>
 * The way keys are created can be summarised as the following. For Edges the
 * resulting cell will be: Source Value + Delimiter + Flag + Delimiter +
 * Destination Value + Delimiter + Flag (And a second edge of Destination Value
 * + Delimiter + Flag + Delimiter + Source Value + Delimiter + Flag for
 * searching)
 * <p>
 * For entities the resulting cell will be: Identifier Value + Delimiter + Flag
 * <p>
 * Note that the Delimiter referenced in the above example is the byte
 * representation of the number 0 for this implementation and the values are
 * appropriately escaped. And the Flag is a byte value that changes depending on
 * whether it being used on an entity, an undirected edge and a directed edge
 * input as the user specified or as the one input inverted for searching. The
 * flag values are as follows: Entity = 1 Undirected Edge = 4 Directed Edge = 2
 * Inverted Directed Edge = 3
 * <p>
 * Values are constructed by placing all the properties in a map of Property
 * Name : Byte Value
 * <p>
 * And then serialising the entire map to bytes.
 */
public class ElementSerialisation {
    protected final Schema schema;

    public ElementSerialisation(final Schema schema) {
        this.schema = schema;
    }

    public byte[] getValue(final String group, final Properties properties)
            throws SerialisationException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final SchemaElementDefinition elementDefinition = schema.getElement(group);
        if (null == elementDefinition) {
            throw new SerialisationException("No SchemaElementDefinition found for group " + group + ", is this group in your schema or do your table iterators need updating?");
        }
        final Iterator<String> propertyNames = elementDefinition.getProperties().iterator();
        String propertyName;
        while (propertyNames.hasNext()) {
            propertyName = propertyNames.next();
            final TypeDefinition typeDefinition = elementDefinition.getPropertyTypeDef(propertyName);
            if (isStoredInValue(propertyName, elementDefinition)) {
                final Serialisation serialiser = (typeDefinition != null) ? typeDefinition.getSerialiser() : null;
                try {
                    if (null != serialiser) {
                        Object value = properties.get(propertyName);
                        if (null != value) {
                            final byte[] bytes = serialiser.serialise(value);
                            writeBytes(bytes, out);
                        } else {
                            final byte[] bytes = serialiser.serialiseNull();
                            writeBytes(bytes, out);
                        }
                    } else {
                        writeBytes(HBaseStoreConstants.EMPTY_BYTES, out);
                    }
                } catch (final IOException e) {
                    throw new SerialisationException("Failed to write serialise property to ByteArrayOutputStream" + propertyName, e);
                }
            }
        }

        return out.toByteArray();
    }

    public byte[] getValue(final Element element) throws SerialisationException {
        return getValue(element.getGroup(), element.getProperties());
    }

    public Properties getPropertiesFromValue(final String group, final byte[] value)
            throws SerialisationException {
        final Properties properties = new Properties();
        if (value == null || value.length == 0) {
            return properties;
        }
        int lastDelimiter = 0;
        final int arrayLength = value.length;
        long currentPropLength;
        final SchemaElementDefinition elementDefinition = schema.getElement(group);
        if (null == elementDefinition) {
            throw new SerialisationException("No SchemaElementDefinition found for group " + group + ", is this group in your schema or do your table iterators need updating?");
        }
        final Iterator<String> propertyNames = elementDefinition.getProperties().iterator();
        while (propertyNames.hasNext() && lastDelimiter < arrayLength) {
            final String propertyName = propertyNames.next();
            if (isStoredInValue(propertyName, elementDefinition)) {
                final TypeDefinition typeDefinition = elementDefinition.getPropertyTypeDef(propertyName);
                final Serialisation<?> serialiser = (typeDefinition != null) ? typeDefinition.getSerialiser() : null;
                if (null != serialiser) {
                    final int numBytesForLength = CompactRawSerialisationUtils.decodeVIntSize(value[lastDelimiter]);
                    final byte[] length = new byte[numBytesForLength];
                    System.arraycopy(value, lastDelimiter, length, 0, numBytesForLength);
                    try {
                        currentPropLength = CompactRawSerialisationUtils.readLong(length);
                    } catch (final SerialisationException e) {
                        throw new SerialisationException("Exception reading length of property");
                    }
                    lastDelimiter += numBytesForLength;
                    if (currentPropLength > 0) {
                        try {
                            properties.put(propertyName, serialiser.deserialise(Arrays.copyOfRange(value, lastDelimiter, lastDelimiter += currentPropLength)));
                        } catch (SerialisationException e) {
                            throw new SerialisationException("Failed to deserialise property " + propertyName, e);
                        }
                    } else {
                        try {
                            properties.put(propertyName, serialiser.deserialiseEmptyBytes());
                        } catch (SerialisationException e) {
                            throw new SerialisationException("Failed to deserialise property " + propertyName, e);
                        }
                    }
                }
            }
        }

        return properties;
    }

    public Element getElement(final Cell cell) throws SerialisationException {
        return getElement(cell, null);
    }

    public Element getElement(final Cell cell, final Map<String, String> options)
            throws SerialisationException {
        final boolean keyRepresentsEntity = doesKeyRepresentEntity(cell);
        if (keyRepresentsEntity) {
            return getEntity(cell);
        }
        return getEdge(cell, options);
    }

    public byte[] buildColumnVisibility(final Element element) throws SerialisationException {
        return buildColumnVisibility(element.getGroup(), element.getProperties());
    }

    public byte[] buildColumnVisibility(final String group, final Properties properties)
            throws SerialisationException {
        final SchemaElementDefinition elementDefinition = schema.getElement(group);
        if (null == elementDefinition) {
            throw new SerialisationException("No SchemaElementDefinition found for group " + group + ", is this group in your schema or do your table iterators need updating?");
        }
        if (null != schema.getVisibilityProperty()) {
            final TypeDefinition propertyDef = elementDefinition.getPropertyTypeDef(schema.getVisibilityProperty());
            if (null != propertyDef) {
                final Object property = properties.get(schema.getVisibilityProperty());
                if (property != null) {
                    try {
                        return propertyDef.getSerialiser().serialise(property);
                    } catch (final SerialisationException e) {
                        throw new SerialisationException(e.getMessage(), e);
                    }
                } else {
                    return propertyDef.getSerialiser().serialiseNull();
                }
            }
        }

        return HBaseStoreConstants.EMPTY_BYTES;
    }

    public Properties getPropertiesFromColumnVisibility(final String group, final byte[] columnVisibility)
            throws SerialisationException {
        final Properties properties = new Properties();

        final SchemaElementDefinition elementDefinition = schema.getElement(group);
        if (null == elementDefinition) {
            throw new SerialisationException("No SchemaElementDefinition found for group " + group + ", is this group in your schema or do your table iterators need updating?");
        }

        if (null != schema.getVisibilityProperty()) {
            final TypeDefinition propertyDef = elementDefinition.getPropertyTypeDef(schema.getVisibilityProperty());
            if (null != propertyDef) {
                final Serialisation serialiser = propertyDef.getSerialiser();
                try {
                    if (columnVisibility == null || columnVisibility.length == 0) {
                        final Object value = serialiser.deserialiseEmptyBytes();
                        if (value != null) {
                            properties.put(schema.getVisibilityProperty(), value);
                        }
                    } else {
                        properties.put(schema.getVisibilityProperty(),
                                serialiser.deserialise(columnVisibility));
                    }
                } catch (final SerialisationException e) {
                    throw new SerialisationException(e.getMessage(), e);
                }
            }
        }

        return properties;
    }

    public byte[] buildColumnQualifier(final Element element) throws SerialisationException {
        return buildColumnQualifier(element.getGroup(), element.getProperties());
    }

    public byte[] buildColumnQualifier(final String group, final Properties properties)
            throws SerialisationException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final SchemaElementDefinition elementDefinition = schema.getElement(group);
        if (null == elementDefinition) {
            throw new SerialisationException("No SchemaElementDefinition found for group " + group + ", is this group in your schema or do your table iterators need updating?");
        }
        final Iterator<String> propertyNames = elementDefinition.getGroupBy().iterator();
        while (propertyNames.hasNext()) {
            String propertyName = propertyNames.next();
            final TypeDefinition typeDefinition = elementDefinition.getPropertyTypeDef(propertyName);
            final Serialisation serialiser = (typeDefinition != null) ? typeDefinition.getSerialiser() : null;
            try {
                if (null != serialiser) {
                    Object value = properties.get(propertyName);
                    if (null != value) {
                        final byte[] bytes = serialiser.serialise(value);
                        writeBytes(bytes, out);
                    } else {
                        final byte[] bytes = serialiser.serialiseNull();
                        writeBytes(bytes, out);
                    }
                } else {
                    writeBytes(HBaseStoreConstants.EMPTY_BYTES, out);
                }
            } catch (final IOException e) {
                throw new SerialisationException("Failed to write serialise property to ByteArrayOutputStream" + propertyName, e);
            }
        }

        return out.toByteArray();
    }

    public Properties getPropertiesFromColumnQualifier(final String group, final byte[] bytes)
            throws SerialisationException {
        final SchemaElementDefinition elementDefinition = schema.getElement(group);
        if (null == elementDefinition) {
            throw new SerialisationException("No SchemaElementDefinition found for group " + group + ", is this group in your schema or do your table iterators need updating?");
        }

        final Properties properties = new Properties();
        if (bytes == null || bytes.length == 0) {
            return properties;
        }

        int lastDelimiter = 0;
        final int arrayLength = bytes.length;
        long currentPropLength;
        final Iterator<String> propertyNames = elementDefinition.getGroupBy().iterator();
        while (propertyNames.hasNext() && lastDelimiter < arrayLength) {
            final String propertyName = propertyNames.next();
            final TypeDefinition typeDefinition = elementDefinition.getPropertyTypeDef(propertyName);
            final Serialisation<?> serialiser = (typeDefinition != null) ? typeDefinition.getSerialiser() : null;
            if (null != serialiser) {
                final int numBytesForLength = CompactRawSerialisationUtils.decodeVIntSize(bytes[lastDelimiter]);
                final byte[] length = new byte[numBytesForLength];
                System.arraycopy(bytes, lastDelimiter, length, 0, numBytesForLength);
                try {
                    currentPropLength = CompactRawSerialisationUtils.readLong(length);
                } catch (final SerialisationException e) {
                    throw new SerialisationException("Exception reading length of property");
                }
                lastDelimiter += numBytesForLength;
                if (currentPropLength > 0) {
                    try {
                        properties.put(propertyName, serialiser.deserialise(Arrays.copyOfRange(bytes, lastDelimiter, lastDelimiter += currentPropLength)));
                    } catch (SerialisationException e) {
                        throw new SerialisationException("Failed to deserialise property " + propertyName, e);
                    }
                }
            }
        }

        return properties;
    }

    public byte[] getPropertiesAsBytesFromColumnQualifier(final String group, final byte[] bytes, final int numProps)
            throws SerialisationException {
        if (numProps == 0 || bytes == null || bytes.length == 0) {
            return HBaseStoreConstants.EMPTY_BYTES;
        }
        final SchemaElementDefinition elementDefinition = schema.getElement(group);
        if (null == elementDefinition) {
            throw new SerialisationException("No SchemaElementDefinition found for group " + group + ", is this group in your schema or do your table iterators need updating?");
        }
        if (numProps == elementDefinition.getProperties().size()) {
            return bytes;
        }
        int lastDelimiter = 0;
        final int arrayLength = bytes.length;
        long currentPropLength;
        int propIndex = 0;
        while (propIndex < numProps && lastDelimiter < arrayLength) {
            final int numBytesForLength = CompactRawSerialisationUtils.decodeVIntSize(bytes[lastDelimiter]);
            final byte[] length = new byte[numBytesForLength];
            System.arraycopy(bytes, lastDelimiter, length, 0, numBytesForLength);
            try {
                currentPropLength = CompactRawSerialisationUtils.readLong(length);
            } catch (final SerialisationException e) {
                throw new SerialisationException("Exception reading length of property");
            }

            lastDelimiter += numBytesForLength;
            if (currentPropLength > 0) {
                lastDelimiter += currentPropLength;
            }

            propIndex++;
        }

        final byte[] propertyBytes = new byte[lastDelimiter];
        System.arraycopy(bytes, 0, propertyBytes, 0, lastDelimiter);
        return propertyBytes;
    }

    public long buildTimestamp(final Properties properties) throws SerialisationException {
        if (null != schema.getTimestampProperty()) {
            final Object property = properties.get(schema.getTimestampProperty());
            if (property == null) {
                return System.currentTimeMillis();
            } else {
                return (Long) property;
            }
        }
        return System.currentTimeMillis();
    }

    /**
     * Get the properties for a given group defined in the Schema as being
     * stored in the HBase timestamp column.
     *
     * @param group     The {@link Element} type to be queried
     * @param timestamp the element timestamp property
     * @return The Properties stored within the Timestamp part of the
     * {@link Cell}
     * @throws SerialisationException If the supplied group has not been defined
     */

    public Properties getPropertiesFromTimestamp(final String group, final long timestamp)
            throws SerialisationException {
        final SchemaElementDefinition elementDefinition = schema.getElement(group);
        if (null == elementDefinition) {
            throw new SerialisationException("No SchemaElementDefinition found for group " + group + ", is this group in your schema or do your table iterators need updating?");
        }

        final Properties properties = new Properties();
        // If the element group requires a timestamp property then add it.
        if (null != schema.getTimestampProperty() && elementDefinition.containsProperty(schema.getTimestampProperty())) {
            properties.put(schema.getTimestampProperty(), timestamp);
        }
        return properties;
    }

    public byte[] serialiseVertex(final Object vertex) throws SerialisationException {
        try {
            return ByteArrayEscapeUtils.escape(this.schema.getVertexSerialiser().serialise(vertex));
        } catch (final SerialisationException e) {
            throw new SerialisationException(
                    "Failed to serialise given identifier object for use in the bloom filter", e);
        }
    }

    public byte[] getEntityKey(final byte[] serialisedVertex, final boolean endKey) {
        byte[] key;
        if (endKey) {
            key = Arrays.copyOf(serialisedVertex, serialisedVertex.length + 3);
            key[key.length - 1] = ByteArrayEscapeUtils.DELIMITER_PLUS_ONE;
        } else {
            key = Arrays.copyOf(serialisedVertex, serialisedVertex.length + 2);
        }
        key[serialisedVertex.length] = ByteArrayEscapeUtils.DELIMITER;
        key[serialisedVertex.length + 1] = ByteEntityPositions.ENTITY;
        return key;
    }


    public boolean doesKeyRepresentEntity(final Cell cell) throws SerialisationException {
        final byte[] row = CellUtil.cloneRow(cell);
        return row[row.length - 1] == ByteEntityPositions.ENTITY;
    }

    protected boolean selfEdge(final Edge edge) {
        return edge.getSource().equals(edge.getDestination());
    }

    protected void addPropertiesToElement(final Element element, final Cell cell)
            throws SerialisationException {
        element.copyProperties(
                getPropertiesFromColumnQualifier(element.getGroup(), CellUtil.cloneQualifier(cell)));
        element.copyProperties(
                getPropertiesFromValue(element.getGroup(), CellUtil.cloneValue(cell)));

//        final List<Tag> visibilityTags = new ArrayList<>();
//        VisibilityUtils.extractVisibilityTags(cell, visibilityTags);
//        byte[] visibility = null;
//        for (Tag visibilityTag : visibilityTags) {
//            visibility = visibilityTag.getValue();
//            break;
//        }
//        element.copyProperties(
//                getPropertiesFromColumnVisibility(element.getGroup(), visibility));
        element.copyProperties(
                getPropertiesFromTimestamp(element.getGroup(), cell.getTimestamp()));
    }

    protected Serialisation getVertexSerialiser() {
        return schema.getVertexSerialiser();
    }

    protected Edge getEdge(final Cell cell, final Map<String, String> options)
            throws SerialisationException {
        final byte[][] result = new byte[3][];
        final boolean directed = getSourceAndDestination(CellUtil.cloneRow(cell), result, options);
        final String group = getGroup(cell);
        try {
            final Edge edge = new Edge(group, getVertexSerialiser().deserialise(result[0]),
                    getVertexSerialiser().deserialise(result[1]), directed);
            addPropertiesToElement(edge, cell);
            return edge;
        } catch (final SerialisationException e) {
            throw new SerialisationException("Failed to re-create Edge from cell", e);
        }
    }

    protected byte[] getSerialisedSource(final Edge edge) throws SerialisationException {
        try {
            return ByteArrayEscapeUtils.escape(getVertexSerialiser().serialise(edge.getSource()));
        } catch (final SerialisationException e) {
            throw new SerialisationException("Failed to serialise Edge Source", e);
        }
    }

    protected byte[] getSerialisedDestination(final Edge edge) throws SerialisationException {
        try {
            return ByteArrayEscapeUtils.escape(getVertexSerialiser().serialise(edge.getDestination()));
        } catch (final SerialisationException e) {
            throw new SerialisationException("Failed to serialise Edge Destination", e);
        }
    }

    protected String getGroup(final Cell cell) throws SerialisationException {
        return getGroup(CellUtil.cloneFamily(cell));
    }

    public String getGroup(final byte[] columnFamily) throws SerialisationException {
        try {
            return new String(columnFamily, CommonConstants.UTF_8);
        } catch (final UnsupportedEncodingException e) {
            throw new SerialisationException(e.getMessage(), e);
        }
    }

    protected boolean isStoredInValue(final String propertyName, final SchemaElementDefinition elementDef) {
        return !elementDef.getGroupBy().contains(propertyName)
                //&& !propertyName.equals(schema.getVisibilityProperty())
                && !propertyName.equals(schema.getTimestampProperty());
    }

    private void writeBytes(final byte[] bytes, final ByteArrayOutputStream out)
            throws IOException {
        CompactRawSerialisationUtils.write(bytes.length, out);
        out.write(bytes);
    }

    @SuppressFBWarnings(value = "BC_UNCONFIRMED_CAST", justification = "If an element is not an Entity it must be an Edge")
    public Pair<byte[]> getRowKeys(final Element element) throws SerialisationException {
        if (element instanceof Entity) {
            return new Pair<>(getRowKey(((Entity) element)));
        }

        return getRowKeys(((Edge) element));
    }

    public byte[] getRowKey(final Entity entity) throws SerialisationException {
        byte[] value;
        try {
            value = ByteArrayEscapeUtils.escape(getVertexSerialiser().serialise(entity.getVertex()));
            final byte[] returnVal = Arrays.copyOf(value, value.length + 2);
            returnVal[returnVal.length - 2] = ByteArrayEscapeUtils.DELIMITER;
            returnVal[returnVal.length - 1] = ByteEntityPositions.ENTITY;
            return returnVal;
        } catch (final SerialisationException e) {
            throw new SerialisationException("Failed to serialise Entity Identifier", e);
        }
    }

    public Pair<byte[]> getRowKeys(final Edge edge) throws SerialisationException {
        byte directionFlag1;
        byte directionFlag2;
        if (edge.isDirected()) {
            directionFlag1 = ByteEntityPositions.CORRECT_WAY_DIRECTED_EDGE;
            directionFlag2 = ByteEntityPositions.INCORRECT_WAY_DIRECTED_EDGE;
        } else {
            directionFlag1 = ByteEntityPositions.UNDIRECTED_EDGE;
            directionFlag2 = ByteEntityPositions.UNDIRECTED_EDGE;
        }
        final byte[] source = getSerialisedSource(edge);
        final byte[] destination = getSerialisedDestination(edge);

        final int length = source.length + destination.length + 5;
        final byte[] rowKey1 = new byte[length];
        System.arraycopy(source, 0, rowKey1, 0, source.length);
        rowKey1[source.length] = ByteArrayEscapeUtils.DELIMITER;
        rowKey1[source.length + 1] = directionFlag1;
        rowKey1[source.length + 2] = ByteArrayEscapeUtils.DELIMITER;
        System.arraycopy(destination, 0, rowKey1, source.length + 3, destination.length);
        rowKey1[rowKey1.length - 2] = ByteArrayEscapeUtils.DELIMITER;
        rowKey1[rowKey1.length - 1] = directionFlag1;
        final byte[] rowKey2 = new byte[length];
        System.arraycopy(destination, 0, rowKey2, 0, destination.length);
        rowKey2[destination.length] = ByteArrayEscapeUtils.DELIMITER;
        rowKey2[destination.length + 1] = directionFlag2;
        rowKey2[destination.length + 2] = ByteArrayEscapeUtils.DELIMITER;
        System.arraycopy(source, 0, rowKey2, destination.length + 3, source.length);
        rowKey2[rowKey2.length - 2] = ByteArrayEscapeUtils.DELIMITER;
        rowKey2[rowKey2.length - 1] = directionFlag2;
        if (selfEdge(edge)) {
            return new Pair<>(rowKey1, null);
        }
        return new Pair<>(rowKey1, rowKey2);
    }

    protected Entity getEntity(final Cell cell) throws SerialisationException {

        try {
            final byte[] row = CellUtil.cloneRow(cell);
            final Entity entity = new Entity(getGroup(cell), getVertexSerialiser()
                    .deserialise(ByteArrayEscapeUtils.unEscape(Arrays.copyOfRange(row, 0, row.length - 2))));
            addPropertiesToElement(entity, cell);
            return entity;
        } catch (final SerialisationException e) {
            throw new SerialisationException("Failed to re-create Entity from cell", e);
        }
    }

    protected boolean getSourceAndDestination(final byte[] rowKey, final byte[][] sourceDestValues,
                                              final Map<String, String> options) throws SerialisationException {
        // Get element class, sourceValue, destinationValue and directed flag from row cell
        // Expect to find 3 delimiters (4 fields)
        final int[] positionsOfDelimiters = new int[3];
        short numDelims = 0;
        // Last byte will be directional flag so don't count it
        for (int i = 0; i < rowKey.length - 1; ++i) {
            if (rowKey[i] == ByteArrayEscapeUtils.DELIMITER) {
                if (numDelims >= 3) {
                    throw new SerialisationException(
                            "Too many delimiters found in row cell - found more than the expected 3.");
                }
                positionsOfDelimiters[numDelims++] = i;
            }
        }
        if (numDelims != 3) {
            throw new SerialisationException(
                    "Wrong number of delimiters found in row cell - found " + numDelims + ", expected 3.");
        }
        // If edge is undirected then create edge
        // (no need to worry about which direction the vertices should go in).
        // If the edge is directed then need to decide which way round the vertices should go.
        byte directionFlag;
        try {
            directionFlag = rowKey[rowKey.length - 1];
        } catch (final NumberFormatException e) {
            throw new SerialisationException("Error parsing direction flag from row cell - " + e);
        }
        if (directionFlag == ByteEntityPositions.UNDIRECTED_EDGE) {
            // Edge is undirected
            sourceDestValues[0] = getSourceBytes(rowKey, positionsOfDelimiters);
            sourceDestValues[1] = getDestBytes(rowKey, positionsOfDelimiters);
            return false;
        } else if (directionFlag == ByteEntityPositions.CORRECT_WAY_DIRECTED_EDGE) {
            // Edge is directed and the first identifier is the source of the edge
            sourceDestValues[0] = getSourceBytes(rowKey, positionsOfDelimiters);
            sourceDestValues[1] = getDestBytes(rowKey, positionsOfDelimiters);
            return true;
        } else if (directionFlag == ByteEntityPositions.INCORRECT_WAY_DIRECTED_EDGE) {
            // Edge is directed and the second identifier is the source of the edge
            int src = 1;
            int dst = 0;
            if (matchEdgeSource(options)) {
                src = 0;
                dst = 1;
            }
            sourceDestValues[src] = getSourceBytes(rowKey, positionsOfDelimiters);
            sourceDestValues[dst] = getDestBytes(rowKey, positionsOfDelimiters);
            return true;
        } else {
            throw new SerialisationException(
                    "Invalid direction flag in row cell - flag was " + directionFlag);
        }
    }

    private byte[] getDestBytes(final byte[] rowKey, final int[] positionsOfDelimiters) {
        return ByteArrayEscapeUtils
                .unEscape(Arrays.copyOfRange(rowKey, positionsOfDelimiters[1] + 1, positionsOfDelimiters[2]));
    }

    private byte[] getSourceBytes(final byte[] rowKey, final int[] positionsOfDelimiters) {
        return ByteArrayEscapeUtils
                .unEscape(Arrays.copyOfRange(rowKey, 0, positionsOfDelimiters[0]));
    }

    private boolean matchEdgeSource(final Map<String, String> options) {
        return options != null
                && options.containsKey(HBaseStoreConstants.OPERATION_RETURN_MATCHED_SEEDS_AS_EDGE_SOURCE)
                && "true".equalsIgnoreCase(options.get(HBaseStoreConstants.OPERATION_RETURN_MATCHED_SEEDS_AS_EDGE_SOURCE));
    }

    public Pair<byte[]> getEdgeOnlyKeys(final byte[] serialisedVertex) {
        final byte[] endKeyBytes = Arrays.copyOf(serialisedVertex, serialisedVertex.length + 3);
        endKeyBytes[serialisedVertex.length] = ByteArrayEscapeUtils.DELIMITER;
        endKeyBytes[serialisedVertex.length + 1] = ByteEntityPositions.UNDIRECTED_EDGE;
        endKeyBytes[serialisedVertex.length + 2] = ByteArrayEscapeUtils.DELIMITER_PLUS_ONE;
        final byte[] startKeyBytes = Arrays.copyOf(serialisedVertex, serialisedVertex.length + 3);
        startKeyBytes[serialisedVertex.length] = ByteArrayEscapeUtils.DELIMITER;
        startKeyBytes[serialisedVertex.length + 1] = ByteEntityPositions.CORRECT_WAY_DIRECTED_EDGE;
        startKeyBytes[serialisedVertex.length + 2] = ByteArrayEscapeUtils.DELIMITER;
        return new Pair<>(startKeyBytes, endKeyBytes);
    }

    public byte[] getEdgeKey(final byte[] serialisedVertex, final boolean endKey) {
        byte[] key;
        if (endKey) {
            key = Arrays.copyOf(serialisedVertex, serialisedVertex.length + 3);
            key[key.length - 1] = ByteArrayEscapeUtils.DELIMITER_PLUS_ONE;
        } else {
            key = Arrays.copyOf(serialisedVertex, serialisedVertex.length + 2);
        }
        key[serialisedVertex.length] = ByteArrayEscapeUtils.DELIMITER;
        key[serialisedVertex.length + 1] = ByteEntityPositions.UNDIRECTED_EDGE;
        return key;
    }
}
