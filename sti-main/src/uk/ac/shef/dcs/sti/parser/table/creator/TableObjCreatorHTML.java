package uk.ac.shef.dcs.sti.parser.table.creator;

import cern.colt.matrix.ObjectMatrix2D;
import org.apache.any23.extractor.html.DomUtils;
import org.w3c.dom.Node;
import uk.ac.shef.dcs.sti.STIEnum;
import uk.ac.shef.dcs.sti.core.model.TCell;
import uk.ac.shef.dcs.sti.core.model.TContext;
import uk.ac.shef.dcs.sti.core.model.Table;
import uk.ac.shef.dcs.sti.core.model.TColumnHeader;
import uk.ac.shef.dcs.sti.util.XPathUtils;

/**
 * Author: Ziqi Zhang (z.zhang@dcs.shef.ac.uk)
 * Date: 26/10/12
 * Time: 10:35
 */
public class TableObjCreatorHTML implements TableObjCreator {

    @Override
    public Table create(ObjectMatrix2D preTable, String tableId, String sourceId, TContext... context) {
        Table table = new Table(tableId, sourceId, preTable.rows()-1, preTable.columns());
        for (TContext ctx : context)
            table.addContext(ctx);

        //firstly add the header row
        for (int c = 0; c < preTable.columns(); c++) {
            Object o = preTable.get(0, c);
            if (o == null) { //a null value will be inserted by TableHODetector if no user defined header was found
                //todo: header column type
                TColumnHeader header = new TColumnHeader(STIEnum.TABLE_HEADER_UNKNOWN.getValue());
                table.setColumnHeader(c,header);

            } else {
                //todo: header column type
                Node e = (Node) o;
                String text = e.getTextContent();
                String xPath= DomUtils.getXPathForNode(e);

                TColumnHeader header = new TColumnHeader(text);
                header.setHeaderXPath(xPath);
                table.setColumnHeader(c,header);
            }
        }

        //then go thru each other rows
        for (int r = 1; r < preTable.rows(); r++) {
            for (int c = 0; c < preTable.columns(); c++) {
                Node e = (Node) preTable.get(r, c);
                String text = e.getTextContent();
                String xPath= DomUtils.getXPathForNode(e);

                TCell cell = new TCell(text);
                cell.setText(text);
                cell.setxPath(xPath);
                //todo: content cell type

                table.setContentCell(r-1,c,cell);

                //handle the table row once
                if (c == 0 && xPath!=null) {
                    String rowXPath = XPathUtils.trimXPathLastTag("TR", xPath);
                    table.getRowXPaths().put(r,rowXPath);
                }
            }
        }

        if(table.getRowXPaths().size()>0){
            String rowXPath=table.getRowXPaths().get(0);
            if(rowXPath==null && table.getRowXPaths().size()>1)
                rowXPath = table.getRowXPaths().get(1);
            if(rowXPath!=null) {
                //System.out.println();
            String tableXPath = XPathUtils.trimXPathLastTag("TABLE", rowXPath);
            table.setTableXPath(tableXPath);
            }
        }

        return table;
    }

}
