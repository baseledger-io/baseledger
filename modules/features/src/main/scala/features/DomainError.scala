package features

enum DomainError(msg: String) extends RuntimeException(msg):
  case NotFound(entity: String, id: String) 
    extends DomainError(s"$entity with ID $id was not found.")
  
  case InvalidOperation(reason: String) 
    extends DomainError(reason)
    
  case Unauthorized(user: String) 
    extends DomainError(s"User $user is not authorized.")
