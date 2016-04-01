package uk.ac.shef.dcs.sti.algorithm.tm;

import javafx.util.Pair;
import org.apache.log4j.Logger;
import uk.ac.shef.dcs.sti.PlaceHolder;
import uk.ac.shef.dcs.sti.nlp.Lemmatizer;
import uk.ac.shef.dcs.sti.nlp.NLPTools;
import uk.ac.shef.dcs.sti.experiment.TableMinerConstants;
import uk.ac.shef.dcs.kbsearch.rep.Clazz;
import uk.ac.shef.dcs.kbsearch.rep.Entity;
import uk.ac.shef.dcs.sti.rep.*;
import uk.ac.shef.dcs.util.CollectionUtils;
import uk.ac.shef.dcs.util.StringUtils;

import java.io.IOException;
import java.util.*;

/**
 * Firstly, computeElementScores each candidate type for this column, based on 1) # of candidate entities lead to that type 2) candidate entity's disamb computeElementScores
 * Then once type is decided for this column, re-computeElementScores disambiguation scores for every candidate entity
 */
public class TMPClazzScorer implements ClazzScorer {

    private static final Logger LOG = Logger.getLogger(TMPClazzScorer.class.getName());

    private Lemmatizer lemmatizer;
    private List<String> stopWords;
    private OntologyBasedBoWCreator bowCreator;
    private double[] wt;  //header text, column, out table ctx: title&caption, out table ctx:other

    public TMPClazzScorer(String nlpResources, OntologyBasedBoWCreator bowCreator, List<String> stopWords,
                          double[] weights) throws IOException {
        this.lemmatizer = NLPTools.getInstance(nlpResources).getLemmatizer();
        this.bowCreator = bowCreator;
        this.stopWords = stopWords;
        this.wt = weights;
    }

    @Override
    public List<TColumnHeaderAnnotation> computeElementScores(List<Pair<Entity, Map<String, Double>>> input,
                                                             Collection<TColumnHeaderAnnotation> headerAnnotationCandidates,
                                                             Table table,
                                                             List<Integer> rows, int column) {
        List<TColumnHeaderAnnotation> candidates = new ArrayList<>();
        for (int row : rows)
            candidates = computeCEScore(input, headerAnnotationCandidates, table, row, column);
        candidates = computeCCScore(candidates, table, column);

        return candidates;
    }


    /**
     * compute concept instance computeElementScores
     *
     * @param entities
     * @param existingHeaderAnnotations
     * @param table
     * @param row
     * @param column
     * @return
     */
    private List<TColumnHeaderAnnotation> computeCEScore(List<Pair<Entity, Map<String, Double>>> entities,
                                                        Collection<TColumnHeaderAnnotation> existingHeaderAnnotations,
                                                        Table table,
                                                        int row, int column) {
        final List<TColumnHeaderAnnotation> updatedHeaderAnnotations =
                new ArrayList<>(existingHeaderAnnotations);

        //for this row
        Entity winningEntity = null;
        double highestScore = 0.0;
        for (Pair<Entity, Map<String, Double>> es : entities) { //each candidate entity in this cell
            Entity entity = es.getKey();
            //each assigned type receives a computeElementScores of 1, and the bonus computeElementScores due to disambiguation result
            double entityCFScore = es.getValue().get(TCellAnnotation.SCORE_FINAL);
            if (entityCFScore > highestScore) {
                highestScore = entityCFScore;
                winningEntity = entity;
            }
        }
        if (entities.size() == 0 || winningEntity == null) {
            //this entity has a computeElementScores of 0.0, it should not contribute to the header typing, but we may still keep it as candidate for this cell
            LOG.warn("no clazz elected by cell: (" + row + "," + column + ")");
            return updatedHeaderAnnotations;
        }


        for (Pair<Entity, Map<String, Double>> es : entities) {
            Entity entity = es.getKey();
            double entityCFScore = es.getValue().get(TCellAnnotation.SCORE_FINAL);
            if (entityCFScore != highestScore)
                continue;

            Set<String> votedClazzByThisCell = new HashSet<>();    //each type will receive a max of 1 vote from each cell. If multiple candidates have the same highest computeElementScores and casts same votes, they are counted oly once
            List<Clazz> votedClazzByThisEntity = entity.getTypes();

            //consolidate scores from this cell
            for (Clazz clazz : votedClazzByThisEntity) {
                if (votedClazzByThisCell.contains(clazz.getId()))
                    continue;

                votedClazzByThisCell.add(clazz.getId());

                String headerText = table.getColumnHeader(column).getHeaderText();

                //is this clazz (of the winning entity) already put into the collection of header annotations?
                TColumnHeaderAnnotation hAnnotation = null;
                for (TColumnHeaderAnnotation headerAnnotation : updatedHeaderAnnotations) {
                    if (headerAnnotation.getHeaderText().equals(headerText)
                            && headerAnnotation.getAnnotation().equals(clazz)) {
                        hAnnotation = headerAnnotation;
                        break;
                    }
                }
                if (hAnnotation == null) {
                    hAnnotation = new TColumnHeaderAnnotation(headerText, clazz, 0.0);
                }
                Map<String, Double> scoreElements = hAnnotation.getScoreElements();
                if (scoreElements == null || scoreElements.size() == 0) {
                    scoreElements = new HashMap<>();
                    scoreElements.put(TColumnHeaderAnnotation.SUM_CE, 0.0);
                    scoreElements.put(TColumnHeaderAnnotation.SUM_ENTITY_VOTE, 0.0);
                }
                scoreElements.put(TColumnHeaderAnnotation.SUM_CE,
                        scoreElements.get(TColumnHeaderAnnotation.SUM_CE) + highestScore);
                scoreElements.put(TColumnHeaderAnnotation.SUM_ENTITY_VOTE,
                        scoreElements.get(TColumnHeaderAnnotation.SUM_ENTITY_VOTE) + 1.0);
                hAnnotation.setScoreElements(scoreElements);

                if(!updatedHeaderAnnotations.contains(hAnnotation))
                    updatedHeaderAnnotations.add(hAnnotation);
            }
        }

        return updatedHeaderAnnotations;
    }


    /**
     * compute concept context computeElementScores
     *
     * @param candidates
     * @param table
     * @param column
     * @return
     */
    public List<TColumnHeaderAnnotation> computeCCScore(Collection<TColumnHeaderAnnotation> candidates,
                                                       Table table, int column) {
        List<String> bowHeader = null,
                bowColumn = null, bowImportantContext = null, bowTrivialContext = null;
        for (TColumnHeaderAnnotation ha : candidates) {
            Double scoreCtxHeader = ha.getScoreElements().get(TColumnHeaderAnnotation.SCORE_CTX_IN_HEADER);
            Double scoreCtxColumn = ha.getScoreElements().get(TColumnHeaderAnnotation.SCORE_CTX_IN_COLUMN);
            Double scoreCtxOutTable = ha.getScoreElements().get(TColumnHeaderAnnotation.SCORE_CTX_OUT);

            if (scoreCtxColumn != null &&
                    scoreCtxHeader != null
                    && scoreCtxOutTable != null)
                continue;

            Set<String> clazzBOW = new HashSet<>(createClazzBOW(ha,
                    true,
                    TableMinerConstants.BOW_DISCARD_SINGLE_CHAR,
                    TableMinerConstants.CLAZZBOW_INCLUDE_URI));

            if (scoreCtxHeader == null) {
                bowHeader = createHeaderTextBOW(bowHeader, table, column);
                double ctx_header_text =
                        CollectionUtils.computeFrequencyWeightedDice(clazzBOW, bowHeader) * wt[0];
                ha.getScoreElements().put(TColumnHeaderAnnotation.SCORE_CTX_IN_HEADER, ctx_header_text);
            }

            if (scoreCtxColumn == null) {
                bowColumn = createColumnBOW(bowColumn, table, column);
                double ctx_column =
                        CollectionUtils.computeFrequencyWeightedDice(clazzBOW, bowColumn) * wt[1];
                //CollectionUtils.computeCoverage(bag_of_words_for_column, new ArrayList<String>(annotation_bow)) * weights[1];
                ha.getScoreElements().put(TColumnHeaderAnnotation.SCORE_CTX_IN_COLUMN, ctx_column);
            }

            if (scoreCtxOutTable == null) {
                bowImportantContext = createImportantOutTableCtxBOW(bowImportantContext, table);
                double ctx_table_major =
                        CollectionUtils.computeFrequencyWeightedDice(clazzBOW, bowImportantContext) * wt[2];
                //CollectionUtils.computeCoverage(bag_of_words_for_table_major_context, new ArrayList<String>(annotation_bow)) * weights[3];
                bowTrivialContext = createTrivialOutTableCtxBOW(bowTrivialContext, table);
                double ctx_table_other =
                        CollectionUtils.computeFrequencyWeightedDice(clazzBOW, bowTrivialContext) * wt[3];
                //CollectionUtils.computeCoverage(bag_of_words_for_table_other_context, new ArrayList<String>(annotation_bow)) * weights[2];
                ha.getScoreElements().put(TColumnHeaderAnnotation.SCORE_CTX_OUT,
                        ctx_table_major + ctx_table_other);
            }

        }

        if(candidates instanceof List)
            return (List<TColumnHeaderAnnotation>)candidates;
        else
            return new ArrayList<>(candidates);
    }


    /*public Map<String, Double> computeFinal(TColumnHeaderAnnotation ha, int tableRowsTotal) {
        Map<String, Double> scoreElements = ha.getScoreElements();
        double sum = 0.0;
        double score_entity_disamb =
                scoreElements.get(TColumnHeaderAnnotation.SUM_CE);

        scoreElements.put(TColumnHeaderAnnotation.SCORE_CE, score_entity_disamb);

        double score_entity_vote = scoreElements.get(TColumnHeaderAnnotation.SUM_ENTITY_VOTE)/(double)tableRowsTotal;
        scoreElements.put(TColumnHeaderAnnotation.SCORE_ENTITY_VOTE, score_entity_vote);

        for (Map.Entry<String, Double> e : scoreElements.entrySet()) {
            if (e.getKey().equals(TColumnHeaderAnnotation.SUM_CE) ||
                    e.getKey().equals(TColumnHeaderAnnotation.SUM_ENTITY_VOTE) ||
                    e.getKey().equals(TColumnHeaderAnnotation.FINAL))
                continue;

            sum += e.getValue();
        }
        scoreElements.put(TColumnHeaderAnnotation.FINAL, sum);
        ha.setFinalScore(sum);
        return scoreElements;
    }*/

    public Map<String, Double> computeFinal(TColumnHeaderAnnotation ha, int tableRowsTotal) {
        Map<String, Double> scoreElements = ha.getScoreElements();
        double ce =
                scoreElements.get(TColumnHeaderAnnotation.SUM_CE);
        double sum_entity_vote = scoreElements.get(TColumnHeaderAnnotation.SUM_ENTITY_VOTE);

        scoreElements.put(TColumnHeaderAnnotation.SCORE_CE, ce / sum_entity_vote);

        double score_entity_vote = scoreElements.get(TColumnHeaderAnnotation.SUM_ENTITY_VOTE) / (double) tableRowsTotal;
        scoreElements.put(TColumnHeaderAnnotation.SCORE_ENTITY_VOTE, score_entity_vote);

        double base_score =ce;

        for (Map.Entry<String, Double> e : scoreElements.entrySet()) {
            if (e.getKey().startsWith("ctx"))
                base_score += e.getValue();
        }
        scoreElements.put(TColumnHeaderAnnotation.FINAL, base_score);
        ha.setFinalScore(base_score);
        return scoreElements;
    }

    /**
     * compute domain concensus
     *
     * @param ha
     * @param domain_representation
     * @return
     */
    @Override
    public double computeDC(TColumnHeaderAnnotation ha, List<String> domain_representation) {
        List<String> annotation_bow = createClazzBOW(ha,
                true,
                TableMinerConstants.BOW_DISCARD_SINGLE_CHAR,
                TableMinerConstants.CLAZZBOW_INCLUDE_URI);
        double score = CollectionUtils.computeFrequencyWeightedDice(annotation_bow, domain_representation);
        score = Math.sqrt(score) * 2;
        ha.getScoreElements().put(TColumnHeaderAnnotation.SCORE_DOMAIN_CONSENSUS, score);

        return score;  //To change body of implemented methods use File | Settings | File Templates.
    }

    private List<String> createImportantOutTableCtxBOW(List<String> bowOutTableCtx, Table table) {
        if (bowOutTableCtx != null)
            return bowOutTableCtx;
        if (table.getContexts() == null)
            return new ArrayList<>();

        List<String> bow = new ArrayList<>();
        for (int i = 0; i < table.getContexts().size(); i++) {
            TContext tx = table.getContexts().get(i);
            if (tx.getType().equals(TContext.TableContextType.PAGETITLE) ||
                    tx.getType().equals(TContext.TableContextType.CAPTION)) {
                bow.addAll(lemmatizer.lemmatize(
                                StringUtils.toBagOfWords(tx.getText(), true, true, TableMinerConstants.BOW_DISCARD_SINGLE_CHAR))
                );
            }
        }
        bow.removeAll(stopWords);
        return bow;
    }

    private List<String> createTrivialOutTableCtxBOW(List<String> bowOutTableCtx, Table table) {
        if (bowOutTableCtx != null)
            return bowOutTableCtx;
        if (table.getContexts() == null)
            return new ArrayList<>();

        List<String> bow = new ArrayList<>();
        for (int i = 0; i < table.getContexts().size(); i++) {
            TContext tx = table.getContexts().get(i);
            if (!tx.getType().equals(TContext.TableContextType.PAGETITLE) &&
                    !tx.getType().equals(TContext.TableContextType.CAPTION)) {
                bow.addAll(lemmatizer.lemmatize(
                                StringUtils.toBagOfWords(tx.getText(), true, true, TableMinerConstants.BOW_DISCARD_SINGLE_CHAR))
                );
            }
        }
        bow.removeAll(stopWords);
        return bow;
    }

    private List<String> createColumnBOW(List<String> bag_of_words_for_column, Table table, int column) {
        if (bag_of_words_for_column != null)
            return bag_of_words_for_column;
        List<String> bow = new ArrayList<>();
        for (int row = 0; row < table.getNumRows(); row++) {
            TCell tcc = table.getContentCell(row, column);
            if (tcc.getText() != null) {
                bow.addAll(lemmatizer.lemmatize(
                                StringUtils.toBagOfWords(tcc.getText(), true, true, TableMinerConstants.BOW_DISCARD_SINGLE_CHAR))
                );
            }
        }
        bow.removeAll(stopWords);
        return bow;
    }

    private List<String> createHeaderTextBOW(List<String> bowHeaderText, Table table, int column) {
        if (bowHeaderText != null)
            return bowHeaderText;
        List<String> bow = new ArrayList<>();

        // for (int c = 0; c < table.getNumCols(); c++) {
        TColumnHeader header = table.getColumnHeader(column);
        if (header != null &&
                header.getHeaderText() != null &&
                !header.getHeaderText().equals(PlaceHolder.TABLE_HEADER_UNKNOWN.getValue())) {
            bow.addAll(lemmatizer.lemmatize(
                            StringUtils.toBagOfWords(header.getHeaderText(), true, true, TableMinerConstants.BOW_DISCARD_SINGLE_CHAR))
            );
        }
        //   }
        bow.removeAll(stopWords);
        //also remove special, generic words, like "title", "name"
        bow.remove("title");
        bow.remove("name");
        return bow;
    }

    private List<String> createClazzBOW(TColumnHeaderAnnotation ha,
                                        boolean lowercase,
                                        boolean discard_single_char,
                                        boolean include_url) {
        List<String> bow = new ArrayList<>();
        if (include_url) {
            bow.addAll(bowCreator.create(ha.getAnnotation().getId()));
        }

        String label = StringUtils.toAlphaNumericWhitechar(ha.getAnnotation().getLabel()).trim();
        if (lowercase)
            label = label.toLowerCase();
        for (String s : label.split("\\s+")) {
            s = s.trim();
            if (s.length() > 0)
                bow.add(s);
        }

        if (discard_single_char) {
            Iterator<String> it = bow.iterator();
            while (it.hasNext()) {
                String t = it.next();
                if (t.length() < 2)
                    it.remove();
            }
        }
        bow.removeAll(TableMinerConstants.FUNCTIONAL_STOPWORDS);
        return bow;
    }


    //todo: this method's formula is redudant, check again
    public static double compute_typing_base_score(double sum_ce,
                                                   double sum_entity_vote,
                                                   double total_table_rows) {
        if (sum_entity_vote == 0)
            return 0.0;

        double score_entity_vote = sum_entity_vote / total_table_rows;
        double base_score = score_entity_vote * (sum_ce / sum_entity_vote);
        return base_score;
    }
}
