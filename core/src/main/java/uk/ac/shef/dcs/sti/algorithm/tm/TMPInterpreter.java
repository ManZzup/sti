package uk.ac.shef.dcs.sti.algorithm.tm;

import javafx.util.Pair;
import org.apache.log4j.Logger;
import uk.ac.shef.dcs.sti.STIException;
import uk.ac.shef.dcs.sti.algorithm.tm.subjectcol.TColumnFeature;
import uk.ac.shef.dcs.sti.algorithm.tm.subjectcol.SubjectColumnDetector;
import uk.ac.shef.dcs.sti.misc.DataTypeClassifier;
import uk.ac.shef.dcs.sti.experiment.TableMinerConstants;
import uk.ac.shef.dcs.sti.rep.*;
import uk.ac.shef.dcs.websearch.bing.v2.APIKeysDepletedException;

import java.io.IOException;
import java.util.*;

/**

 */
public class TMPInterpreter {

    private SubjectColumnDetector subjectColumnDetector;
    private LEARNING learning;
    private DataLiteralColumnClassifier interpreter_column_with_knownReltaions;
    private BinaryRelationInterpreter interpreter_relation;
    private HeaderBinaryRelationScorer hbr_scorer;
    //private static Logger LOG = Logger.getLogger(MainInterpreter.class.getName());
    private Set<Integer> ignoreCols;
    private int[] mustdoColumns;
    private UPDATE update;

    private static final Logger LOG = Logger.getLogger(TMPInterpreter.class.getName());

    public TMPInterpreter(SubjectColumnDetector subjectColumnDetector,
                          LEARNING learning,
                          UPDATE update,
                          BinaryRelationInterpreter interpreter_relation,
                          HeaderBinaryRelationScorer hbr_scorer,
                          DataLiteralColumnClassifier interpreter_column_with_knownReltaions,
                          int[] ignoreColumns,
                          int[] mustdoColumns
                          ) {
        this.subjectColumnDetector = subjectColumnDetector;
        this.learning = learning;
        this.interpreter_column_with_knownReltaions = interpreter_column_with_knownReltaions;
        this.interpreter_relation = interpreter_relation;
        this.ignoreCols = new HashSet<>();
        for(int i: ignoreColumns)
            ignoreCols.add(i);
        this.mustdoColumns = mustdoColumns;
        this.update = update;
        this.hbr_scorer = hbr_scorer;
    }

    public TAnnotation start(Table table, boolean relationLearning) throws IOException, APIKeysDepletedException, STIException, STIException {
        //1. find the main subject column of this table
        LOG.info(">\t Detecting subject column...");
        int[] ignoreColumnsArray = new int[ignoreCols.size()];

        int index=0;
        for(Integer i: ignoreCols) {
            ignoreColumnsArray[index] = i;
            index++;
        }
        List<Pair<Integer, Pair<Double, Boolean>>> subjectColumnScores =
                subjectColumnDetector.compute(table, ignoreColumnsArray);
        //ignore columns that are likely to be acronyms only, because they are highly ambiguous
        /*if (candidate_main_NE_columns.size() > 1) {
            Iterator<ObjObj<Integer, ObjObj<Double, Boolean>>> it = candidate_main_NE_columns.iterator();
            while (it.hasNext()) {
                ObjObj<Integer, ObjObj<Double, Boolean>> en = it.next();
                if (en.getOtherObject().getOtherObject() == true)
                    it.remove();
            }
        }*/
        TAnnotation tableAnnotations = new TAnnotation(table.getNumRows(), table.getNumCols());
        tableAnnotations.setSubjectColumn(subjectColumnScores.get(0).getKey());

        List<Integer> annotatedColumns = new ArrayList<>();
        LOG.info(">\t LEARNING phrase ...");
        for (int col = 0; col < table.getNumCols(); col++) {
            /*if(col!=1)
                continue;*/
            if (isCompulsoryColumn(col)) {
                LOG.info("\t>> Column=(compulsory)" + col);
                annotatedColumns.add(col);
                learning.process(table, tableAnnotations, col);
            } else {
                if (ignoreColumn(col)) continue;
                if (!table.getColumnHeader(col).getFeature().getMostFrequentDataType().getType().equals(DataTypeClassifier.DataType.NAMED_ENTITY))
                    continue;
                /*if (table.getColumnHeader(col).getFeature().isAcronymColumn())
                    continue;*/
                annotatedColumns.add(col);

                //if (tab_annotations.getRelationAnnotationsBetween(main_subject_column, col) == null) {
                LOG.info("\t>> Column=" + col);
                learning.process(table, tableAnnotations, col);
                //}
            }
        }

        if (update != null) {
            LOG.info(">\t UPDATE phase ...");
            update.update(annotatedColumns, table, tableAnnotations);
        }

        if (relationLearning) {
            double best_solution_score = 0;
            int main_subject_column = -1;
            TAnnotation best_annotations = null;
            for (Pair<Integer, Pair<Double, Boolean>> mainCol : subjectColumnScores) {
                //tab_annotations = new TAnnotation(table.getNumRows(), table.getNumCols());
                main_subject_column = mainCol.getKey();
                if (ignoreColumn(main_subject_column)) continue;

                LOG.info(">\t Interpret relations with the main column, =" + main_subject_column);
                int columns_having_relations_with_main_col = interpreter_relation.interpret(tableAnnotations, table, main_subject_column);
                boolean interpretable = false;
                if (columns_having_relations_with_main_col > 0) {
                    interpretable = true;
                }
                if (interpretable) {
                    tableAnnotations.setSubjectColumn(main_subject_column);
                    break;
                } else {
                    //the current subject column could be wrong, try differently
                    double overall_score_of_current_solution = scoreSolution(tableAnnotations, table, main_subject_column);
                    if (overall_score_of_current_solution > best_solution_score) {
                        tableAnnotations.setSubjectColumn(main_subject_column);
                        best_annotations = tableAnnotations;
                        best_solution_score = overall_score_of_current_solution;
                    }
                    tableAnnotations.resetRelationAnnotations();
                    LOG.error(">>\t Main column does not satisfy number of relations check, continue to the next main column...");
                    continue;
                }
            }
            if (tableAnnotations == null && best_annotations != null) {
                tableAnnotations = best_annotations;
            }

            if (TableMinerConstants.REVISE_HBR_BY_DC && update !=null) {
                List<String> domain_rep = update.construct_domain_represtation(table, tableAnnotations, annotatedColumns);
                revise_header_binary_relations(tableAnnotations, domain_rep);
            }

            //4. consolidation-for columns that have relation with main subject column, if the column is
            // entity column, do column typing and disambiguation; otherwise, simply create header annotation
            LOG.info(">\t Classify columns (non-NE) in relation with main column");
            interpreter_column_with_knownReltaions.interpret(table, tableAnnotations, annotatedColumns.toArray(new Integer[0]));

        }


        return tableAnnotations;
    }

    /*private boolean isInterpretable(int columns_having_relations_with_main_col, Table table) {
        int totalColumns = 0;
        for (int col = 0; col < table.getNumCols(); col++) {
            DataTypeClassifier.DataType cType = table.getColumnHeader(col).getFeature().getMostFrequentDataType().getType();
            if (cType.equals(DataTypeClassifier.DataType.ORDERED_NUMBER) ||
                    cType.equals(DataTypeClassifier.DataType.EMPTY) ||
                    cType.equals(DataTypeClassifier.DataType.LONG_TEXT))
                continue;
            totalColumns++;
        }

        return columns_having_relations_with_main_col >=
                totalColumns * interpreter_relation.getThreshold_minimum_binary_relations_in_table();
    }*/

    private double scoreSolution(TAnnotation tab_annotations, Table table, int main_subject_column) {
        double entityScores = 0.0;
        for (int col = 0; col < table.getNumCols(); col++) {
            for (int row = 0; row < table.getNumRows(); row++) {
                TCellAnnotation[] cAnns = tab_annotations.getContentCellAnnotations(row, col);
                if (cAnns != null && cAnns.length > 0) {
                    entityScores += cAnns[0].getFinalScore();
                }
            }
        }

        double relationScores = 0.0;
        for (Map.Entry<Key_SubjectCol_ObjectCol, List<HeaderBinaryRelationAnnotation>> entry : tab_annotations.getRelationAnnotations_across_columns().entrySet()) {
            Key_SubjectCol_ObjectCol key = entry.getKey();
            HeaderBinaryRelationAnnotation rel = entry.getValue().get(0);
            relationScores += rel.getFinalScore();
        }
        TColumnFeature cf = table.getColumnHeader(main_subject_column).getFeature();
        //relationScores = relationScores * cf.getValueDiversity();

        double diversity = cf.getUniqueCellCount() + cf.getUniqueTokenCount();
        return (entityScores + relationScores) * diversity * ((table.getNumRows() - cf.getEmptyCellCount()) / (double) table.getNumRows());
    }

    private boolean ignoreColumn(Integer i) {
        if (i != null) {
            for (int a : ignoreCols) {
                if (a == i)
                    return true;
            }
        }
        return false;
    }

    private boolean isCompulsoryColumn(Integer i) {
        if (i != null) {
            for (int a : mustdoColumns) {
                if (a == i)
                    return true;
            }
        }
        return false;
    }

    private void revise_header_binary_relations(TAnnotation annotation, List<String> domain_representation
    ) {
        for (Map.Entry<Key_SubjectCol_ObjectCol, List<HeaderBinaryRelationAnnotation>>
                entry : annotation.getRelationAnnotations_across_columns().entrySet()) {

            for (HeaderBinaryRelationAnnotation hbr : entry.getValue()) {
                double domain_consensus = hbr_scorer.score_domain_consensus(hbr, domain_representation);

                hbr.setFinalScore(hbr.getFinalScore() + domain_consensus);
            }
            Collections.sort(entry.getValue());
        }

    }

}
