package uk.ac.shef.dcs.sti.algorithm.ji;

import uk.ac.shef.dcs.sti.algorithm.tm.EntityScorer;
import uk.ac.shef.dcs.sti.rep.CellAnnotation;
import uk.ac.shef.dcs.kbsearch.rep.Entity;
import uk.ac.shef.dcs.sti.rep.TContentCell;
import uk.ac.shef.dcs.sti.rep.Table;
import uk.ac.shef.dcs.sti.util.CosineSimilarity;
import uk.ac.shef.dcs.sti.util.JaccardSimilarity;
import uk.ac.shef.wit.simmetrics.similaritymetrics.AbstractStringMetric;
import uk.ac.shef.wit.simmetrics.similaritymetrics.Levenshtein;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 */
public class JIAdaptedEntityScorer implements EntityScorer {
    public static String SCORE_CELL_FACTOR ="score_factor_graph-cell";
    public static String SCORE_LEV = "stringsim_lev";
    public static String SCORE_JACCARD = "stringsim_jaccard";
    public static String SCORE_COSINE="stringsim_cosine";

    private AbstractStringMetric cosine;
    private AbstractStringMetric jaccard;
    private AbstractStringMetric lev;

    public JIAdaptedEntityScorer() {
        cosine = new CosineSimilarity();
        jaccard = new JaccardSimilarity();
        lev =new Levenshtein();
    }

    @Override
    public Map<String, Double> score(Entity candidate,
                                     List<Entity> all_candidates,
                                     int entity_source_column,
                                     int entity_source_row,
                                     List<Integer> other_entity_source_rows,
                                     Table table,
                                     Set<String> assigned_column_semantic_types,
                                     Entity... reference_disambiguated_entities) {

        TContentCell cell = table.getContentCell(entity_source_row, entity_source_column);
        double levScore = calculateStringSimilarity(cell.getText(), candidate, lev);
        //dice between NE and cell text
        double jaccardScore = calculateStringSimilarity(cell.getText(), candidate, jaccard);
        double cosineScore = calculateStringSimilarity(cell.getText(), candidate, cosine);

        Map<String, Double> score_elements = new HashMap<String, Double>();
        score_elements.put(SCORE_CELL_FACTOR, levScore+jaccardScore+cosineScore);
        score_elements.put(SCORE_COSINE,cosineScore);
        score_elements.put(SCORE_JACCARD,jaccardScore);
        score_elements.put(SCORE_LEV, levScore);
        return score_elements;
    }

    private double calculateStringSimilarity(String text, Entity candidate, AbstractStringMetric lev) {
        double baseScore = lev.getSimilarity(text, candidate.getLabel());
        return baseScore;
    }

    @Override
    public double computeFinal(Map<String, Double> scoreMap, String cellTextOriginal) {
        scoreMap.put(CellAnnotation.SCORE_FINAL, scoreMap.get(SCORE_CELL_FACTOR));
        return scoreMap.get(SCORE_CELL_FACTOR);
    }
}