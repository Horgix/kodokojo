/**
 * Kodo Kojo - Software factory done right
 * Copyright © 2016 Kodo Kojo (infos@kodokojo.io)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package io.kodokojo.model;


import java.io.Serializable;
import java.util.Set;

import static org.apache.commons.lang.StringUtils.isBlank;

public class Stack implements Serializable {

    private final String name;

    private final StackType stackType;

    private final Set<BrickState> brickStates;

    public Stack(String name, StackType stackType, Set<BrickState> brickStates) {
        if (isBlank(name)) {
            throw new IllegalArgumentException("name must be defined.");
        }
        if (stackType == null) {
            throw new IllegalArgumentException("stackType must be defined.");
        }
        this.name = name;
        this.stackType = stackType;
        this.brickStates = brickStates;
    }

    public String getName() {
        return name;
    }


    public StackType getStackType() {
        return stackType;
    }

    public Set<BrickState> getBrickStates() {
        return brickStates;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Stack stack = (Stack) o;

        if (!name.equals(stack.name)) return false;
        if (stackType != stack.stackType) return false;
        return brickStates.equals(stack.brickStates);

    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + stackType.hashCode();
        result = 31 * result + brickStates.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "Stack{" +
                "name='" + name + '\'' +
                ", stackType=" + stackType +
                ", brickStates=" + brickStates +
                '}';
    }
}
