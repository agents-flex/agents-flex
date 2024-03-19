package com.agentsflex.store.condition;

public class Condition implements Operand {

    protected ConditionType type;
    protected Operand left;
    protected Operand right;
    protected boolean effective = true;
    protected Connector connector;
    protected Condition prev;
    protected Condition next;

    public Condition() {
    }

    public Condition(ConditionType type, Operand left, Operand right) {
        this.type = type;
        this.left = left;
        this.right = right;
    }

    public Condition and(Condition next) {
        return new Group(this).and(next);
    }

    public Condition or(Condition next) {
        return new Group(this).or(next);
    }

    public void connect(Condition nextCondition, Connector connector) {
        if (this.next != null) {
            this.next.connect(nextCondition, connector);
        } else {
            nextCondition.connector = connector;
            this.next = nextCondition;
            nextCondition.prev = this;
        }
    }

    public boolean checkEffective() {
        return effective;
    }

    protected Condition getPrevEffectiveCondition() {
        if (prev == null) {
            return null;
        }
        return prev.checkEffective() ? prev : prev.getPrevEffectiveCondition();
    }

    protected Condition getNextEffectiveCondition() {
        if (next == null) {
            return null;
        }
        return next.checkEffective() ? next : next.getNextEffectiveCondition();
    }


    @Override
    public String toExpression(ExpressionAdaptor adaptor) {
        StringBuilder expr = new StringBuilder();
        if (checkEffective()) {
            Condition prevEffectiveCondition = getPrevEffectiveCondition();
            if (prevEffectiveCondition != null && this.connector != null) {
                expr.append(adaptor.toConnector(this.connector));
            }

            expr.append(adaptor.toLeft(this.left));
            expr.append(adaptor.toType(this.type));
            expr.append(adaptor.toRight(this.right));
        }

        if (this.next != null) {
            expr.append(this.next.toExpression(adaptor));
        }

        return expr.toString();
    }

    @Override
    public String toString() {
        return "Condition{" +
            "type=" + type +
            ", left=" + left +
            ", right=" + right +
            ", effective=" + effective +
            ", connector=" + connector +
            ", prev=" + prev +
            ", next=" + next +
            '}';
    }
}
