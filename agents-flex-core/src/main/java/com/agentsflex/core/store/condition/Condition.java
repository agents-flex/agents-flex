package com.agentsflex.core.store.condition;

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

        if (left instanceof Value) {
            ((Value) left).setCondition(this);
        }
        if (right instanceof Value) {
            ((Value) right).setCondition(this);
        }
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
            expr.append(adaptor.toCondition(this));
        }

        if (this.next != null) {
            expr.append(this.next.toExpression(adaptor));
        }

        return expr.toString();
    }


    public ConditionType getType() {
        return type;
    }

    public void setType(ConditionType type) {
        this.type = type;
    }

    public Operand getLeft() {
        return left;
    }

    public void setLeft(Operand left) {
        this.left = left;
    }

    public Operand getRight() {
        return right;
    }

    public void setRight(Operand right) {
        this.right = right;
    }

    public boolean isEffective() {
        return effective;
    }

    public void setEffective(boolean effective) {
        this.effective = effective;
    }

    public Connector getConnector() {
        return connector;
    }

    public void setConnector(Connector connector) {
        this.connector = connector;
    }

    public Condition getPrev() {
        return prev;
    }

    public void setPrev(Condition prev) {
        this.prev = prev;
    }

    public Condition getNext() {
        return next;
    }

    public void setNext(Condition next) {
        this.next = next;
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
