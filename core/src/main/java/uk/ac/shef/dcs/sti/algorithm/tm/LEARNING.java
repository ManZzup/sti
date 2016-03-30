package uk.ac.shef.dcs.sti.algorithm.tm;

import javafx.util.Pair;
import uk.ac.shef.dcs.sti.rep.TCellAnnotation;
import uk.ac.shef.dcs.kbsearch.rep.Entity;
import uk.ac.shef.dcs.sti.rep.TAnnotation;
import uk.ac.shef.dcs.sti.rep.Table;

import java.io.IOException;
import java.util.*;

/**
 * Represents the LEARNING phase, creates preliminary column classification and cell disambiguation
 */
public class LEARNING {

    private LEARNINGPreliminaryClassify columnTagger;
    private LEARNINGPreliminaryDisamb cellTagger;
    private int max_reference_entities;


    public LEARNING(LEARNINGPreliminaryClassify columnTagger, LEARNINGPreliminaryDisamb cellTagger,
                    int max_reference_entities) {
        this.columnTagger = columnTagger;
        this.cellTagger = cellTagger;
        this.max_reference_entities = max_reference_entities;
    }

    public void process(Table table, TAnnotation tableAnnotation, int column) throws IOException {
        Pair<Integer, List<List<Integer>>> converge_position =
                columnTagger.learn_seeding(table, tableAnnotation, column);
        Set<Entity> reference_entities = new HashSet<>();
        if (max_reference_entities>0) {
            reference_entities = selectReferenceEntities(table, tableAnnotation, column,max_reference_entities);
        }
        cellTagger.learn_consolidate(converge_position.getKey(),
                converge_position.getValue(),
                table,
                tableAnnotation,
                column,
                reference_entities);
    }

    public static Set<Entity> selectReferenceEntities(Table table, TAnnotation table_annotation, int column, int max){
        List<TCellAnnotation> selected_best_from_each_row = new ArrayList<TCellAnnotation>();
        for(int i=0; i<table.getNumRows(); i++){
            List<TCellAnnotation> best = table_annotation.getBestContentCellAnnotations(i, column);
            if(best!=null && best.size()>0)
                selected_best_from_each_row.addAll(best);
        }
        Collections.sort(selected_best_from_each_row);
        Set<Entity> result = new HashSet<>();
        for(int i=0; i<selected_best_from_each_row.size() && i<max; i++)
            result.add(selected_best_from_each_row.get(i).getAnnotation());
        return result;
    }

    public static Set<Entity> selectReferenceEntities(Pair<Integer, int[]> converge_position, TAnnotation table_annotation, int column, int max){
        Set<Entity> reference_entities = new HashSet<>();
        for (int i = 0; i < converge_position.getKey()&&i<max; i++) {
            int row = converge_position.getValue()[i];
            TCellAnnotation[] annotations = table_annotation.getContentCellAnnotations(row, column);
            if (annotations != null && annotations.length > 0)
                reference_entities.add(annotations[0].getAnnotation());
        }
        return reference_entities;
    }
}
