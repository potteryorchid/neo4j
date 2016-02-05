/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.proc;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.neo4j.kernel.api.exceptions.ProcedureException;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.api.proc.ProcedureSignature.FieldSignature;
import org.neo4j.messages.Messages;
import org.neo4j.procedure.Name;

import static org.neo4j.messages.Messages.proc_argument_missing_name;
import static org.neo4j.messages.Messages.proc_argument_name_empty;
import static org.neo4j.messages.Messages.proc_unmappable_argument_type;

/**
 * Given a java method, figures out a valid {@link org.neo4j.kernel.api.proc.ProcedureSignature} field signature.
 * Basically, it takes the java signature and spits out the same signature described as Neo4j types.
 */
public class MethodSignatureCompiler
{
    private final TypeMappers typeMappers;

    public MethodSignatureCompiler( TypeMappers typeMappers )
    {
        this.typeMappers = typeMappers;
    }

    public List<FieldSignature> signatureFor( Method method ) throws ProcedureException
    {
        Parameter[] params = method.getParameters();
        Type[] types = method.getGenericParameterTypes();
        List<FieldSignature> signature = new ArrayList<>(params.length);
        for ( int i = 0; i < params.length; i++ )
        {
            Parameter param = params[i];
            Type type = types[i];

            if ( !param.isAnnotationPresent( Name.class ) )
            {
                throw new ProcedureException( Status.Procedure.FailedRegistration,
                        Messages.get( proc_argument_missing_name, i, method.getName(),
                        Name.class.getSimpleName()) );
            }
            String name = param.getAnnotation( Name.class ).value();

            if( name.trim().length() == 0 )
            {
                throw new ProcedureException( Status.Procedure.FailedRegistration,
                        Messages.get( proc_argument_name_empty, i, method.getName() ) );
            }

            try
            {
                signature.add(new FieldSignature( name, typeMappers.neoTypeFor( type ) ));
            }
            catch ( ProcedureException e )
            {
                throw new ProcedureException( e.status(),
                        Messages.get( proc_unmappable_argument_type,
                            name, i, method.getName(), param.getType().getSimpleName(),
                            e.getMessage() ) );
            }

        }

        return signature;
    }
}
