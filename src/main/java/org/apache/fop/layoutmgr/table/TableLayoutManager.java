/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* $Id: TableLayoutManager.java 1658340 2015-02-09 09:53:37Z ssteiner $ */

package org.apache.fop.layoutmgr.table;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.fop.area.Area;
import org.apache.fop.area.Block;
import org.apache.fop.area.Trait;
import org.apache.fop.datatypes.LengthBase;
import org.apache.fop.fo.Constants;
import org.apache.fop.fo.FONode;
import org.apache.fop.fo.FObj;
import org.apache.fop.fo.flow.Marker;
import org.apache.fop.fo.flow.Markers;
import org.apache.fop.fo.flow.RetrieveTableMarker;
import org.apache.fop.fo.flow.table.Table;
import org.apache.fop.fo.flow.table.TableColumn;
import org.apache.fop.fo.properties.CommonBorderPaddingBackground;
import org.apache.fop.fo.properties.KeepProperty;
import org.apache.fop.layoutmgr.BlockLevelEventProducer;
import org.apache.fop.layoutmgr.BreakElement;
import org.apache.fop.layoutmgr.BreakOpportunity;
import org.apache.fop.layoutmgr.KnuthElement;
import org.apache.fop.layoutmgr.KnuthGlue;
import org.apache.fop.layoutmgr.LayoutContext;
import org.apache.fop.layoutmgr.LeafPosition;
import org.apache.fop.layoutmgr.ListElement;
import org.apache.fop.layoutmgr.Position;
import org.apache.fop.layoutmgr.PositionIterator;
import org.apache.fop.layoutmgr.SpacedBorderedPaddedBlockLayoutManager;
import org.apache.fop.layoutmgr.TraitSetter;
import org.apache.fop.traits.BorderProps;
import org.apache.fop.traits.MinOptMax;
import org.apache.fop.traits.SpaceVal;
import org.apache.fop.util.BreakUtil;

/**
 * LayoutManager for a table FO.
 * A table consists of columns, table header, table footer and multiple
 * table bodies.
 * The header, footer and body add the areas created from the table cells.
 * The table then creates areas for the columns, bodies and rows
 * the render background.
 */
public class TableLayoutManager extends SpacedBorderedPaddedBlockLayoutManager
        implements BreakOpportunity {

    /**
     * logging instance
     */
    private static Log log = LogFactory.getLog(TableLayoutManager.class);

    private TableContentLayoutManager contentLM;
    private ColumnSetup columns;

    private Block curBlockArea;

    private double tableUnit;
    private boolean autoLayout = true;

    private int halfBorderSeparationBPD;
    private int halfBorderSeparationIPD;

    /** See {@link TableLayoutManager#registerColumnBackgroundArea(TableColumn, Block, int)}. */
    private List columnBackgroundAreas;

    private Position auxiliaryPosition;

    // this holds a possible list of TCLMs that needed to have their addAreas() repeated
    private List<TableCellLayoutManager> savedTCLMs;
    private boolean areAllTCLMsSaved;

    private Markers tableMarkers;
    private Markers tableFragmentMarkers;

    private boolean hasRetrieveTableMarker;

    private boolean repeatedHeader;

    private List<List<KnuthElement>> headerFootnotes = Collections.emptyList();

    private List<List<KnuthElement>> footerFootnotes = Collections.emptyList();

    /**
     * Temporary holder of column background informations for a table-cell's area.
     *
     * @see TableLayoutManager#registerColumnBackgroundArea(TableColumn, Block, int)
     */
    private static final class ColumnBackgroundInfo {
        private TableColumn column;
        private Block backgroundArea;
        private int xShift;

        private ColumnBackgroundInfo(TableColumn column, Block backgroundArea, int xShift) {
            this.column = column;
            this.backgroundArea = backgroundArea;
            this.xShift = xShift;
        }
    }

    /**
     * Create a new table layout manager.
     * @param node the table FO
     */
    public TableLayoutManager(Table node) {
        super(node);
        this.columns = new ColumnSetup(node);
    }


    @Override
    protected CommonBorderPaddingBackground getCommonBorderPaddingBackground() {
        return getTable().getCommonBorderPaddingBackground();
    }


    /** @return the table FO */
    public Table getTable() {
        return (Table)this.fobj;
    }

    /**
     * @return the column setup for this table.
     */
    public ColumnSetup getColumns() {
        return this.columns;
    }

    /** {@inheritDoc} */
    public void initialize() {
        foSpaceBefore = new SpaceVal(
                getTable().getCommonMarginBlock().spaceBefore, this).getSpace();
        foSpaceAfter = new SpaceVal(
                getTable().getCommonMarginBlock().spaceAfter, this).getSpace();
        startIndent = getTable().getCommonMarginBlock().startIndent.getValue(this);
        endIndent = getTable().getCommonMarginBlock().endIndent.getValue(this);

        if (getTable().isSeparateBorderModel()) {
            this.halfBorderSeparationBPD = getTable().getBorderSeparation().getBPD().getLength()
                    .getValue(this) / 2;
            this.halfBorderSeparationIPD = getTable().getBorderSeparation().getIPD().getLength()
                    .getValue(this) / 2;
        } else {
            this.halfBorderSeparationBPD = 0;
            this.halfBorderSeparationIPD = 0;
        }

        if (!getTable().isAutoLayout()
                && getTable().getInlineProgressionDimension().getOptimum(this).getEnum()
                    != EN_AUTO) {
            autoLayout = false;
        }
    }

    private void resetSpaces() {
        this.discardBorderBefore = false;
        this.discardBorderAfter = false;
        this.discardPaddingBefore = false;
        this.discardPaddingAfter = false;
        this.effSpaceBefore = null;
        this.effSpaceAfter = null;
    }

    /**
     * @return half the value of border-separation.block-progression-dimension, or 0 if
     * border-collapse="collapse".
     */
    public int getHalfBorderSeparationBPD() {
        return halfBorderSeparationBPD;
    }

    /**
     * @return half the value of border-separation.inline-progression-dimension, or 0 if
     * border-collapse="collapse".
     */
    public int getHalfBorderSeparationIPD() {
        return halfBorderSeparationIPD;
    }

    /** {@inheritDoc} */
    public List getNextKnuthElements(LayoutContext context, int alignment) {

        List returnList = new LinkedList();

        /*
         * Compute the IPD and adjust it if necessary (overconstrained)
         */
        referenceIPD = context.getRefIPD();
        if (getTable().getInlineProgressionDimension().getOptimum(this).getEnum() != EN_AUTO) {
            int contentIPD = getTable().getInlineProgressionDimension().getOptimum(this)
                    .getLength().getValue(this);
            updateContentAreaIPDwithOverconstrainedAdjust(contentIPD);
        } else {
            if (!getTable().isAutoLayout()) {
                BlockLevelEventProducer eventProducer = BlockLevelEventProducer.Provider.get(
                        getTable().getUserAgent().getEventBroadcaster());
                eventProducer.tableFixedAutoWidthNotSupported(this, getTable().getLocator());
            }
            updateContentAreaIPDwithOverconstrainedAdjust();
        }
        int sumOfColumns = columns.getSumOfColumnWidths(this);
        if (!autoLayout && sumOfColumns > getContentAreaIPD()) {
            log.debug(FONode.decorateWithContextInfo(
                    "The sum of all column widths is larger than the specified table width.",
                    getTable()));
            updateContentAreaIPDwithOverconstrainedAdjust(sumOfColumns);
        }
        int availableIPD = referenceIPD - getIPIndents();
        if (getContentAreaIPD() > availableIPD) {
            BlockLevelEventProducer eventProducer = BlockLevelEventProducer.Provider.get(
                    getTable().getUserAgent().getEventBroadcaster());
            eventProducer.objectTooWide(this, getTable().getName(),
                    getContentAreaIPD(), context.getRefIPD(),
                    getTable().getLocator());
        }

        /* initialize unit to determine computed values
         * for proportional-column-width()
         */
        if (tableUnit == 0.0) {
            this.tableUnit = columns.computeTableUnit(this);
        }

        if (!firstVisibleMarkServed) {
            addKnuthElementsForSpaceBefore(returnList, alignment);
        }

        if (getTable().isSeparateBorderModel()) {
            addKnuthElementsForBorderPaddingBefore(returnList, !firstVisibleMarkServed);
            firstVisibleMarkServed = true;
            // Border and padding to be repeated at each break
            // This must be done only in the separate-border model, as in collapsing
            // tables have no padding and borders are determined at the cell level
            addPendingMarks(context);
        }


        // Elements for the table-header/footer/body
        List contentKnuthElements;
        contentLM = new TableContentLayoutManager(this);
        LayoutContext childLC = LayoutContext.newInstance();
        /*
        childLC.setStackLimit(
              MinOptMax.subtract(context.getStackLimit(),
                                 stackSize));*/
        childLC.setRefIPD(context.getRefIPD());
        childLC.copyPendingMarksFrom(context);

        contentKnuthElements = contentLM.getNextKnuthElements(childLC, alignment);
        //Set index values on elements coming from the content LM
        Iterator iter = contentKnuthElements.iterator();
        while (iter.hasNext()) {
            ListElement el = (ListElement)iter.next();
            notifyPos(el.getPosition());
        }
        log.debug(contentKnuthElements);
        wrapPositionElements(contentKnuthElements, returnList);

        context.updateKeepWithPreviousPending(getKeepWithPrevious());
        context.updateKeepWithPreviousPending(childLC.getKeepWithPreviousPending());

        context.updateKeepWithNextPending(getKeepWithNext());
        context.updateKeepWithNextPending(childLC.getKeepWithNextPending());

        if (getTable().isSeparateBorderModel()) {
            addKnuthElementsForBorderPaddingAfter(returnList, true);
        }
        addKnuthElementsForSpaceAfter(returnList, alignment);

        if (!context.suppressBreakBefore()) {
            //addKnuthElementsForBreakBefore(returnList, context);
            int breakBefore = BreakUtil.compareBreakClasses(getTable().getBreakBefore(),
                    childLC.getBreakBefore());
            if (breakBefore != Constants.EN_AUTO) {
                returnList.add(0, new BreakElement(new LeafPosition(getParent(), 0), 0,
                        -KnuthElement.INFINITE, breakBefore, context));
            }
        }

        //addKnuthElementsForBreakAfter(returnList, context);
        int breakAfter = BreakUtil.compareBreakClasses(getTable().getBreakAfter(),
                childLC.getBreakAfter());
        if (breakAfter != Constants.EN_AUTO) {
            returnList.add(new BreakElement(new LeafPosition(getParent(), 0),
                    0, -KnuthElement.INFINITE, breakAfter, context));
        }

        setFinished(true);
        resetSpaces();
        return returnList;
    }

    /** {@inheritDoc} */
    public Position getAuxiliaryPosition() {
        /*
         * Redefined to return a LeafPosition instead of a NonLeafPosition. The
         * SpaceResolver.SpaceHandlingBreakPosition constructors unwraps all
         * NonLeafPositions, which can lead to a NPE when a break in a table occurs at a
         * page with different ipd.
         */
        if (auxiliaryPosition == null) {
            auxiliaryPosition = new LeafPosition(this, 0);
        }
        return auxiliaryPosition;
    }

    /**
     * Registers the given area, that will be used to render the part of column background
     * covered by a table-cell. If percentages are used to place the background image, the
     * final bpd of the (fraction of) table that will be rendered on the current page must
     * be known. The traits can't then be set when the areas for the cell are created
     * since at that moment this bpd is yet unknown. So they will instead be set in
     * TableLM's {@link #addAreas(PositionIterator, LayoutContext)} method.
     *
     * @param column the table-column element from which the cell gets background
     * informations
     * @param backgroundArea the block of the cell's dimensions that will hold the column
     * background
     * @param xShift additional amount by which the image must be shifted to be correctly
     * placed (to counterbalance the cell's start border)
     */
    void registerColumnBackgroundArea(TableColumn column, Block backgroundArea, int xShift) {
        addBackgroundArea(backgroundArea);
        if (columnBackgroundAreas == null) {
            columnBackgroundAreas = new ArrayList();
        }
        columnBackgroundAreas.add(new ColumnBackgroundInfo(column, backgroundArea, xShift));
    }

    /**
     * The table area is a reference area that contains areas for
     * columns, bodies, rows and the contents are in cells.
     *
     * @param parentIter the position iterator
     * @param layoutContext the layout context for adding areas
     */
    public void addAreas(PositionIterator parentIter,
                         LayoutContext layoutContext) {
        getParentArea(null);
        addId();

        // add space before, in order to implement display-align = "center" or "after"
        if (layoutContext.getSpaceBefore() != 0) {
            addBlockSpacing(0.0, MinOptMax.getInstance(layoutContext.getSpaceBefore()));
        }

        int startXOffset = getTable().getCommonMarginBlock().startIndent.getValue(this);

        // add column, body then row areas

        // BPD of the table, i.e., height of its content; table's borders and paddings not counted
        int tableHeight = 0;
        //Body childLM;
        LayoutContext lc = LayoutContext.offspringOf(layoutContext);


        lc.setRefIPD(getContentAreaIPD());
        contentLM.setStartXOffset(startXOffset);
        contentLM.addAreas(parentIter, lc);

//        Object borderOpt = fobj.getUserAgent().getRendererOptions().get("overpaint-table-borders");

//        if (borderOpt != null && borderOpt instanceof Boolean && (Boolean)borderOpt) {
            overPaintBorders();
//        }

        tableHeight += contentLM.getUsedBPD();

        curBlockArea.setBPD(tableHeight);

        if (columnBackgroundAreas != null) {
            for (Iterator iter = columnBackgroundAreas.iterator(); iter.hasNext();) {
                ColumnBackgroundInfo b = (ColumnBackgroundInfo) iter.next();
                TraitSetter.addBackground(b.backgroundArea,
                        b.column.getCommonBorderPaddingBackground(), this,
                        b.xShift, -b.backgroundArea.getYOffset(),
                        b.column.getColumnWidth().getValue(this), tableHeight);
            }
            columnBackgroundAreas.clear();
        }

        if (getTable().isSeparateBorderModel()) {
            TraitSetter.addBorders(curBlockArea,
                    getTable().getCommonBorderPaddingBackground(),
                    discardBorderBefore, discardBorderAfter, false, false, this);
            TraitSetter.addPadding(curBlockArea,
                    getTable().getCommonBorderPaddingBackground(),
                    discardPaddingBefore, discardPaddingAfter, false, false, this);
        }
        TraitSetter.addBackground(curBlockArea,
                getTable().getCommonBorderPaddingBackground(),
                this);
        TraitSetter.addMargins(curBlockArea,
                getTable().getCommonBorderPaddingBackground(),
                startIndent, endIndent,
                this);
        TraitSetter.addBreaks(curBlockArea,
                getTable().getBreakBefore(), getTable().getBreakAfter());
        TraitSetter.addSpaceBeforeAfter(curBlockArea, layoutContext.getSpaceAdjust(),
                effSpaceBefore, effSpaceAfter);

        flush();

        resetSpaces();
        curBlockArea = null;

        notifyEndOfLayout();
    }

    private void overPaintBorders() {

        List<Block> newBlocks = new ArrayList<Block>();

        List<Object> childAreas = new ArrayList<Object>(curBlockArea.getChildAreas());
        Collections.sort(childAreas, new Comparator<Object>() {
            public int compare(Object o1, Object o2) {
                Block b1 = (Block)o1;
                Block b2 = (Block)o2;
                Integer paddingStart1 = (Integer)b1.getTrait(Trait.PADDING_START);
                Integer paddingStart2 = (Integer)b2.getTrait(Trait.PADDING_START);
                int x1 = b1.getXOffset() - (paddingStart1 != null ? paddingStart1 : 0);
                int x2 = b2.getXOffset() - (paddingStart2 != null ? paddingStart2 : 0);
                if (x1 > x2)
                    return 1;
                else if (x1 < x2)
                    return -1;
                else {
                    if (b1.getYOffset() > b2.getYOffset())
                        return 1;
                    else if (b1.getYOffset() < b2.getYOffset())
                        return -1;
                    else
                        return 0;
                }
            };
        });

        mergeBordersOfType(newBlocks, childAreas,
                new Integer[] {Trait.BORDER_BEFORE, Trait.BORDER_AFTER});

        Collections.sort(childAreas, new Comparator<Object>() {
            public int compare(Object o1, Object o2) {
                Block b1 = (Block)o1;
                Block b2 = (Block)o2;
                Integer paddingStart1 = (Integer)b1.getTrait(Trait.PADDING_START);
                Integer paddingStart2 = (Integer)b2.getTrait(Trait.PADDING_START);
                int x1 = b1.getXOffset() - (paddingStart1 != null ? paddingStart1 : 0);
                int x2 = b2.getXOffset() - (paddingStart2 != null ? paddingStart2 : 0);
                if (b1.getYOffset() > b2.getYOffset())
                    return 1;
                else if (b1.getYOffset() < b2.getYOffset())
                    return -1;
                else {
                    if (x1 > x2)
                        return 1;
                    else if (x1 < x2)
                        return -1;
                    else {
                        return 0;
                    }
                }
            };
        });

        mergeBordersOfType(newBlocks,childAreas,
                new Integer[] {Trait.BORDER_START, Trait.BORDER_END});

        for (Block borderBlock : newBlocks) {
            curBlockArea.addBlock(borderBlock);
        }
    }


    private void mergeBordersOfType(List<Block> newBlocks, List<?> childAreas, Integer[] borderTraits) {

        HashMap<Integer, HashMap<Point, Block>> mergeMap = new HashMap<Integer, HashMap<Point,Block>>();
        for (Integer traitType : borderTraits) {
            mergeMap.put(traitType, null);
        }

        for (Object child : childAreas) {
            Block childBlock = (Block)child;

            log.debug("Block - x=" + childBlock.getXOffset() + ",y=" + childBlock.getYOffset());

            BorderProps startBps = (BorderProps)childBlock.getTrait(Trait.BORDER_START);
            BorderProps endBps = (BorderProps)childBlock.getTrait(Trait.BORDER_END);
            BorderProps beforeBps = (BorderProps)childBlock.getTrait(Trait.BORDER_BEFORE);
            BorderProps afterBps = (BorderProps)childBlock.getTrait(Trait.BORDER_AFTER);

            for (Integer traitType : borderTraits) {

                Block currBlock = childBlock;

                BorderProps borderProps = (BorderProps)currBlock.getTrait(traitType);
                if (borderProps == null) {
                    continue;
                }


                log.debug("Merge: " + traitType);

                HashMap<Point, Block> currTraitMap = mergeMap.get(traitType);
                Point endPoint = this.getEndMiddlePoint(currBlock, traitType, startBps, endBps,
                        beforeBps, afterBps);

                BorderProps bpsCurr = (BorderProps) currBlock.getTrait(traitType);

                Block prevBlock = null;
                if (currTraitMap == null)
                {
                    currTraitMap = new HashMap<Point, Block>();
                    mergeMap.put(traitType, currTraitMap);
                }
                else
                {
                    Point startPoint = this.getStartMiddlePoint(currBlock, traitType, startBps, endBps, beforeBps, afterBps);

                    log.debug("Start now");
                    Iterator<Point> iter = currTraitMap.keySet().iterator();
                    while (iter.hasNext()) {
                        Point prevEndPoint = iter.next();
                        log.debug("end: " + prevEndPoint);
                        log.debug("start: " + startPoint);
                        boolean isVertical = traitType == Trait.BORDER_START || traitType == Trait.BORDER_END;
                        boolean isHorizontal = traitType == Trait.BORDER_BEFORE || traitType == Trait.BORDER_AFTER;

                        if ((isHorizontal && prevEndPoint.y == startPoint.y && prevEndPoint.x >= startPoint.x)
                                || (isVertical && prevEndPoint.x == startPoint.x && prevEndPoint.y >= startPoint.y)) {
                            log.debug("try merge");

                            Block prevBlockCurr = currTraitMap.get(prevEndPoint);
                            iter.remove();
                            BorderProps bpsPrev = (BorderProps) prevBlockCurr.getTrait(traitType);
                            if (this.canMergeBorders(bpsPrev, bpsCurr)) {
                                prevBlock = prevBlockCurr;
                            }

                            break;
                        }
                    }
                }

                Block borderBlock;
                if (prevBlock != null && newBlocks.contains(prevBlock)) {
                    borderBlock = prevBlock;
                } else {
                    borderBlock = new Block();
                    borderBlock.addTrait(Trait.IS_REFERENCE_AREA, Boolean.TRUE);
                    borderBlock.setPositioning(Block.ABSOLUTE);
                    borderBlock.setBidiLevel(currBlock.getBidiLevel());
                    newBlocks.add(borderBlock);

                    BorderProps prevBeforeBps = (BorderProps)currBlock.getTrait(Trait.BORDER_BEFORE);
                    int prevBefore = prevBeforeBps != null ? prevBeforeBps.width : 0;
                    Integer prevPaddingStart = (Integer)currBlock.getTrait(Trait.PADDING_START);
                    Integer prevPaddingEnd = (Integer)currBlock.getTrait(Trait.PADDING_END);
                    Integer prevPaddingBefore = (Integer)currBlock.getTrait(Trait.PADDING_BEFORE);
                    Integer prevPaddingAfter = (Integer)currBlock.getTrait(Trait.PADDING_AFTER);

                    if (traitType == Trait.BORDER_START) {
                        borderBlock.setYOffset(currBlock.getYOffset()
                                + prevBefore);
                        borderBlock.setXOffset(currBlock.getXOffset()
                                - (prevPaddingStart != null ? prevPaddingStart : 0));
                    } else if (traitType == Trait.BORDER_END) {
                        borderBlock.setYOffset(currBlock.getYOffset()
                                + prevBefore);
                        borderBlock.setXOffset(currBlock.getXOffset()
                                - (prevPaddingStart != null ? prevPaddingStart : 0));
                        borderBlock.setIPD(currBlock.getIPD()
                                + (prevPaddingStart != null ? prevPaddingStart : 0)
                                + (prevPaddingEnd != null ? prevPaddingEnd : 0));
                    } else if (traitType == Trait.BORDER_BEFORE) {
                        borderBlock.setYOffset(currBlock.getYOffset());
                        borderBlock.setXOffset(currBlock.getXOffset()
                                - (prevPaddingStart != null ? prevPaddingStart : 0));
                    } else if (traitType == Trait.BORDER_AFTER) {
                        borderBlock.setYOffset(currBlock.getYOffset()
                                + prevBefore);
                        borderBlock.setXOffset(currBlock.getXOffset()
                                - (prevPaddingStart != null ? prevPaddingStart : 0));
                        borderBlock.setBPD(currBlock.getBPD()
                                + (prevPaddingBefore != null ? prevPaddingBefore : 0)
                                + (prevPaddingAfter != null ? prevPaddingAfter : 0));
                    }
                }

                Integer paddingEnd = (Integer)currBlock.getTrait(Trait.PADDING_END);
                Integer paddingAfter = (Integer)currBlock.getTrait(Trait.PADDING_AFTER);
                if (traitType == Trait.BORDER_BEFORE || traitType == Trait.BORDER_AFTER) {
                    int newEndPoint = currBlock.getXOffset()
                            + currBlock.getIPD()
                            + (paddingEnd != null ? paddingEnd : 0);
                    borderBlock.setIPD(newEndPoint - borderBlock.getXOffset());
                } else if (traitType == Trait.BORDER_START
                        || traitType == Trait.BORDER_END) {
                    int newEndPoint = currBlock.getYOffset()
                            + currBlock.getBPD()
                            + currBlock.getBorderAndPaddingWidthBefore()
                            + (paddingAfter != null ? paddingAfter : 0);
                    borderBlock.setBPD(newEndPoint - borderBlock.getYOffset());
                }

                BorderProps newBps = new BorderProps(bpsCurr.style, bpsCurr.width, 0,
                        0, bpsCurr.color/*new Color(255, 0, 0)*/, bpsCurr.getMode());
                borderBlock.addTrait(traitType, newBps);
                //
                currBlock = borderBlock;

                log.debug("merge!");

                currTraitMap.put(endPoint, currBlock);
            }
        }
    }

    private boolean canMergeBorders(BorderProps bpsPrev, BorderProps bpsCurr) {
        return bpsPrev.style == bpsCurr.style
                /*&& org.apache.xmlgraphics.java2d.color.ColorUtil.isSameColor(
                                bpsPrev.color, bpsCurr.color)*/
                && bpsPrev.width == bpsCurr.width
                && bpsPrev.getMode() == bpsPrev.getMode()
                && bpsPrev.getRadiusEnd() == 0
                && bpsCurr.getRadiusStart() == 0;
    }


    private Point getEndMiddlePoint(Block block, int borderTrait, BorderProps startBps,
            BorderProps endBps, BorderProps beforeBps, BorderProps afterBps) {
        int x, y;
        if (borderTrait == Trait.BORDER_START) {
            Integer paddingStart = (Integer)block.getTrait(Trait.PADDING_START);
            x = block.getXOffset()
                    - (paddingStart != null ? paddingStart : 0)
                    - BorderProps.getClippedWidth(startBps);
            y = block.getYOffset() + block.getBPD()
            + block.getBorderAndPaddingWidthBefore()
            + block.getBorderAndPaddingWidthAfter();
        } else if (borderTrait == Trait.BORDER_END) {
            Integer paddingEnd = (Integer)block.getTrait(Trait.PADDING_END);
            x = block.getXOffset() + block.getIPD()
            + (paddingEnd != null ? paddingEnd : 0)
            + BorderProps.getClippedWidth(endBps);
            y = block.getYOffset() + block.getBPD()
            + block.getBorderAndPaddingWidthBefore()
            + block.getBorderAndPaddingWidthAfter();
        } else if (borderTrait == Trait.BORDER_AFTER) {
            Integer paddingEnd = (Integer)block.getTrait(Trait.PADDING_END);
            x = block.getXOffset() + block.getIPD()
            + (paddingEnd != null ? paddingEnd : 0)
            + BorderProps.getClippedWidth(endBps);
            Integer paddingAfter = (Integer)block.getTrait(Trait.PADDING_AFTER);
            y = block.getYOffset() + block.getBPD()
            + block.getBorderAndPaddingWidthBefore()
            + (paddingAfter != null ? paddingAfter : 0)
            + BorderProps.getClippedWidth(afterBps);
        } else if (borderTrait == Trait.BORDER_BEFORE) {
            Integer paddingEnd = (Integer)block.getTrait(Trait.PADDING_END);
            x = block.getXOffset() + block.getIPD()
            + (paddingEnd != null ? paddingEnd : 0)
            + BorderProps.getClippedWidth(endBps);
            y = block.getYOffset()
                    + BorderProps.getClippedWidth(beforeBps);
        } else {
            throw new IllegalArgumentException("Invalid trait: " + borderTrait);
        }
        return new Point(x, y);
    }

    private Point getStartMiddlePoint(Block block, int borderTrait, BorderProps startBps, BorderProps endBps,
            BorderProps beforeBps, BorderProps afterBps) {
        int x, y;
        if (borderTrait == Trait.BORDER_START) {
            Integer paddingStart = (Integer)block.getTrait(Trait.PADDING_START);
            x = block.getXOffset()
                    - (paddingStart != null ? paddingStart : 0)
                    - BorderProps.getClippedWidth(startBps);
            y = block.getYOffset();
        } else if (borderTrait == Trait.BORDER_BEFORE) {
            x = block.getXOffset() - block.getBorderAndPaddingWidthStart();
            y = block.getYOffset()
                    + BorderProps.getClippedWidth(beforeBps);
        } else if (borderTrait == Trait.BORDER_END) {
            Integer paddingEnd = (Integer)block.getTrait(Trait.PADDING_END);
            x = block.getXOffset() + block.getIPD()
            + (paddingEnd != null ? paddingEnd : 0)
            + BorderProps.getClippedWidth(endBps);
            y = block.getYOffset();
        } else if (borderTrait == Trait.BORDER_AFTER) {
            x = block.getXOffset() - block.getBorderAndPaddingWidthStart();
            Integer paddingAfter = (Integer)block.getTrait(Trait.PADDING_AFTER);
            y = block.getYOffset() + block.getBorderAndPaddingWidthBefore()
            + block.getBPD()
            + (paddingAfter != null ? paddingAfter : 0)
            + BorderProps.getClippedWidth(afterBps);
        } else {
            throw new IllegalArgumentException("Invalid trait: " + borderTrait);
        }
        return new Point(x, y);
    }


    /**
     * Return an Area which can contain the passed childArea. The childArea
     * may not yet have any content, but it has essential traits set.
     * In general, if the LayoutManager already has an Area it simply returns
     * it. Otherwise, it makes a new Area of the appropriate class.
     * It gets a parent area for its area by calling its parent LM.
     * Finally, based on the dimensions of the parent area, it initializes
     * its own area. This includes setting the content IPD and the maximum
     * BPD.
     *
     * @param childArea the child area
     * @return the parent area of the child
     */
    public Area getParentArea(Area childArea) {
        if (curBlockArea == null) {
            curBlockArea = new Block();
            // Set up dimensions
            // Must get dimensions from parent area
            /*Area parentArea =*/ parentLayoutManager.getParentArea(curBlockArea);

            TraitSetter.setProducerID(curBlockArea, getTable().getId());

            curBlockArea.setIPD(getContentAreaIPD());

            setCurrentArea(curBlockArea);
        }
        return curBlockArea;
    }

    /**
     * Add the child area to this layout manager.
     *
     * @param childArea the child area to add
     */
    public void addChildArea(Area childArea) {
        if (curBlockArea != null) {
            curBlockArea.addBlock((Block) childArea);
        }
    }

    /**
     * Adds the given area to this layout manager's area, without updating the used bpd.
     *
     * @param background an area
     */
    void addBackgroundArea(Block background) {
        curBlockArea.addChildArea(background);
    }

    /** {@inheritDoc} */
    public int negotiateBPDAdjustment(int adj, KnuthElement lastElement) {
        // TODO Auto-generated method stub
        return 0;
    }

    /** {@inheritDoc} */
    public void discardSpace(KnuthGlue spaceGlue) {
        // TODO Auto-generated method stub

    }

    /** {@inheritDoc} */
    public KeepProperty getKeepTogetherProperty() {
        return getTable().getKeepTogether();
    }

    /** {@inheritDoc} */
    public KeepProperty getKeepWithPreviousProperty() {
        return getTable().getKeepWithPrevious();
    }

    /** {@inheritDoc} */
    public KeepProperty getKeepWithNextProperty() {
        return getTable().getKeepWithNext();
    }

    // --------- Property Resolution related functions --------- //

    /**
     * {@inheritDoc}
     */
    public int getBaseLength(int lengthBase, FObj fobj) {
        // Special handler for TableColumn width specifications
        if (fobj instanceof TableColumn && fobj.getParent() == getFObj()) {
            switch (lengthBase) {
            case LengthBase.CONTAINING_BLOCK_WIDTH:
                return getContentAreaIPD();
            case LengthBase.TABLE_UNITS:
                return (int) this.tableUnit;
            default:
                log.error("Unknown base type for LengthBase.");
                return 0;
            }
        } else {
            switch (lengthBase) {
            case LengthBase.TABLE_UNITS:
                return (int) this.tableUnit;
            default:
                return super.getBaseLength(lengthBase, fobj);
            }
        }
    }

    /** {@inheritDoc} */
    public void reset() {
        super.reset();
        curBlockArea = null;
        tableUnit = 0.0;
    }

    /**
     * Saves a TableCellLayoutManager for later use.
     *
     * @param tclm a TableCellLayoutManager that has a RetrieveTableMarker
     */
    protected void saveTableHeaderTableCellLayoutManagers(TableCellLayoutManager tclm) {
        if (savedTCLMs == null) {
            savedTCLMs = new ArrayList<TableCellLayoutManager>();
        }
        if (!areAllTCLMsSaved) {
            savedTCLMs.add(tclm);
        }
    }

    /**
     * Calls addAreas() for each of the saved TableCellLayoutManagers.
     */
    protected void repeatAddAreasForSavedTableHeaderTableCellLayoutManagers() {
        if (savedTCLMs == null) {
            return;
        }
        // if we get to this stage then we are at the footer of the table fragment; this means that no more
        // different TCLM need to be saved (we already have all); we flag the list as being complete then
        areAllTCLMsSaved = true;
        for (int i = 0; i < savedTCLMs.size(); i++) {
            TableCellLayoutManager tclm = savedTCLMs.get(i);
            if (this.repeatedHeader) {
                tclm.setHasRepeatedHeader(true);
            }
            tclm.repeatAddAreas();
        }
    }

    /**
     * Resolves a RetrieveTableMarker by finding a qualifying Marker to which it is bound to.
     * @param rtm the RetrieveTableMarker to be resolved
     * @return a bound RetrieveTableMarker instance or null if no qualifying Marker found
     */
    public RetrieveTableMarker resolveRetrieveTableMarker(RetrieveTableMarker rtm) {
        String name = rtm.getRetrieveClassName();
        int originalPosition = rtm.getPosition();
        boolean changedPosition = false;

        Marker mark = null;
        // try the primary retrieve scope area, which is the same as table-fragment
        mark = (tableFragmentMarkers == null) ? null : tableFragmentMarkers.resolve(rtm);
        if (mark == null && rtm.getBoundary() != Constants.EN_TABLE_FRAGMENT) {
            rtm.changePositionTo(Constants.EN_LAST_ENDING);
            changedPosition = true;
            // try the page scope area
            mark = getCurrentPV().resolveMarker(rtm);
            if (mark == null && rtm.getBoundary() != Constants.EN_PAGE) {
                // try the table scope area
                mark = (tableMarkers == null) ? null : tableMarkers.resolve(rtm);
            }
        }
        if (changedPosition) {
            // so that the next time it is called looks unchanged
            rtm.changePositionTo(originalPosition);
        }
        if (mark == null) {
            log.debug("found no marker with name: " + name);
            return null;
        } else {
            rtm.bindMarker(mark);
            return rtm;
        }
    }

    /**
     * Register the markers for this table.
     *
     * @param marks the map of markers to add
     * @param starting if the area being added is starting or ending
     * @param isfirst if the area being added has is-first trait
     * @param islast if the area being added has is-last trait
     */
    public void registerMarkers(Map<String, Marker> marks, boolean starting, boolean isfirst,
            boolean islast) {
        if (tableMarkers == null) {
            tableMarkers = new Markers();
        }
        tableMarkers.register(marks, starting, isfirst, islast);
        if (tableFragmentMarkers == null) {
            tableFragmentMarkers = new Markers();
        }
        tableFragmentMarkers.register(marks, starting, isfirst, islast);
    }

    /**
     * Clears the list of markers in the current table fragment. Should be called just before starting a new
     * header (that belongs to the next table fragment).
     */
    protected void clearTableFragmentMarkers() {
        tableFragmentMarkers = null;
    }

    public void flagAsHavingRetrieveTableMarker() {
        hasRetrieveTableMarker = true;
    }

    protected void possiblyRegisterMarkersForTables(Map<String, Marker> markers, boolean isStarting,
            boolean isFirst, boolean isLast) {
        // note: if we allow table-footer after a table-body this check should not be made and the markers
        // should be registered regardless because the retrieval may be done only in the footer
        if (hasRetrieveTableMarker) {
            registerMarkers(markers, isStarting, isFirst, isLast);
        }
        super.possiblyRegisterMarkersForTables(markers, isStarting, isFirst, isLast);
    }

    void setHeaderFootnotes(List<List<KnuthElement>> footnotes) {
        this.headerFootnotes = footnotes;
    }

    List<List<KnuthElement>> getHeaderFootnotes() {
        return headerFootnotes;
    }

    void setFooterFootnotes(List<List<KnuthElement>> footnotes) {
        this.footerFootnotes = footnotes;
    }

    public void setRepeateHeader(boolean repeateHeader) {
        this.repeatedHeader = repeateHeader;
    }

    List<List<KnuthElement>> getFooterFootnotes() {
        return footerFootnotes;
    }

}
