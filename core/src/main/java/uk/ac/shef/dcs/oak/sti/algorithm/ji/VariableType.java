package uk.ac.shef.dcs.oak.sti.algorithm.ji;

import com.hp.hpl.jena.sparql.core.Var;

/**
 * Created by zqz on 12/05/2015.
 */
public enum VariableType {

    CELL("cell"),
    HEADER("header"),
    RELATION("relation");

    private String label;
    private VariableType(String label){
        this.label=label;
    }
    public String toString(){
        return label;
    }

}
