export * from './administracinDeUsuarios.service';
import { AdministracinDeUsuariosService } from './administracinDeUsuarios.service';
export * from './autenticacin.service';
import { AutenticacinService } from './autenticacin.service';
export const APIS = [AdministracinDeUsuariosService, AutenticacinService];
